package fu.hl.g64.motorflow.auth.service;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.auth.config.AuthProperties;
import fu.hl.g64.motorflow.auth.dto.AuthResponse;
import fu.hl.g64.motorflow.auth.dto.ChangePasswordRequest;
import fu.hl.g64.motorflow.auth.dto.CurrentUserResponse;
import fu.hl.g64.motorflow.auth.dto.LoginRequest;
import fu.hl.g64.motorflow.auth.entity.AuthSession;
import fu.hl.g64.motorflow.auth.repository.AuthSessionRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** UC-01 Log In and UC-02 Change Password, plus the session lifecycle behind them. */
@Service
public class AuthService {

    /** A parallel refresh (two tabs) may present the token just replaced; only later is it treated as stolen. */
    private static final Duration REUSE_GRACE = Duration.ofSeconds(10);
    // shortcut: forgets unknown-username lockouts when it grows past this or the app restarts; fine for one instance.
    private static final int MAX_UNKNOWN_USERNAMES = 10_000;

    private final UserAccountRepository users;
    private final AuthSessionRepository sessions;
    private final AuditLogRepository auditLog;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties properties;
    private final Clock clock;
    /** Checked against when the username does not exist, so both cases take the same time. */
    private final String dummyPasswordHash;
    /**
     * Unsaved stand-ins for usernames that do not exist. They count wrong passwords and lock like real
     * accounts, so MSG12/MSG11 do not reveal which usernames exist.
     */
    private final Map<String, UserAccount> unknownUsers = new ConcurrentHashMap<>();

    public AuthService(UserAccountRepository users, AuthSessionRepository sessions, AuditLogRepository auditLog, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuthProperties properties, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.auditLog = auditLog;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Tokens to hand to the client: the body for JSON, the refresh token for the cookie. */
    public record IssuedTokens(AuthResponse body, String refreshToken) {
    }

    /**
     * UC-01. Failed attempts are committed even though the method throws, hence noRollbackFor.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public IssuedTokens login(LoginRequest request, String userAgent, String ipAddress) {
        String username = UserAccount.normalizeUsername(request.username());
        String password = request.password() == null ? "" : request.password();
        // Spaces inside a password are kept; a password of only spaces counts as blank.
        if (username.isEmpty() || password.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG08");
        }

        LocalDateTime now = now();
        UserAccount user = users.findByUsernameForUpdate(username).orElse(null);
        if (user == null) {
            UserAccount unknown = unknownUser(username, now);
            synchronized (unknown) {
                checkPassword(unknown, password, now, ipAddress); // always throws: the dummy hash matches nothing
            }
        }
        checkPassword(user, password, now, ipAddress);
        // Checked after the password so that a stranger cannot learn which accounts exist and are locked.
        if (user.isLockedByAdmin()) {
            throw new BusinessException(HttpStatus.LOCKED, "MSG10");
        }

        user.registerSuccessfulLogin(now);
        audit(user, "LOGIN_SUCCESS", ipAddress, now);
        String refreshToken = RefreshTokens.generate();
        AuthSession session = sessions.save(AuthSession.open(user, RefreshTokens.hash(refreshToken), now,
                now.plus(properties.refreshTokenTtl()), userAgent, ipAddress));
        return issue(user, session, refreshToken);
    }

    /** Gives a new access token and rotates the refresh token. */
    @Transactional(noRollbackFor = BusinessException.class)
    public IssuedTokens refresh(String refreshToken) {
        LocalDateTime now = now();
        String hash = RefreshTokens.hash(refreshToken);
        AuthSession session = sessions.findByRefreshTokenHashForUpdate(hash).orElse(null);
        if (session == null) {
            revokeIfReused(hash, now);
            throw sessionExpired();
        }
        if (!session.isActive(now)) {
            throw sessionExpired();
        }
        UserAccount user = session.getUser();
        if (user.isLockedByAdmin()) {
            session.revoke(now, AuthSession.RevokeReason.ACCOUNT_LOCKED);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "MSG10");
        }
        String newRefreshToken = RefreshTokens.generate();
        LocalDateTime expiresAt = min(now.plus(properties.refreshTokenTtl()),
                session.getCreatedAt().plus(properties.sessionMaxLifetime()));
        session.rotate(RefreshTokens.hash(newRefreshToken), now, expiresAt);
        return issue(user, session, newRefreshToken);
    }

    /**
     * Ends the session found by the refresh cookie or by the access token, whichever the client
     * still has. Always succeeds, so logging out twice is harmless.
     */
    @Transactional
    public void logout(String refreshToken, Long sessionId) {
        LocalDateTime now = now();
        if (refreshToken != null) {
            sessions.findByRefreshTokenHash(RefreshTokens.hash(refreshToken))
                    .ifPresent(s -> s.revoke(now, AuthSession.RevokeReason.LOGOUT));
        }
        if (sessionId != null) {
            sessions.findById(sessionId).ifPresent(s -> s.revoke(now, AuthSession.RevokeReason.LOGOUT));
        }
    }

    /**
     * Called for every request that carries an access token: the session must still be open and
     * the account not locked by Admin.
     */
    @Transactional(readOnly = true)
    public AuthenticatedUser authenticate(long userId, long sessionId) {
        AuthSession session = sessions.findWithUserById(sessionId)
                .filter(s -> s.isActive(now()) && s.getUser().getId() == userId)
                .orElseThrow(AuthService::sessionExpired);
        UserAccount user = session.getUser();
        if (user.isLockedByAdmin()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "MSG10");
        }
        return toPrincipal(user, session.getId());
    }

    /**
     * UC-02. Other devices are signed out, because a password change usually means the old one
     * was temporary or known to someone else.
     */
    @Transactional
    public void changePassword(AuthenticatedUser principal, ChangePasswordRequest request) {
        LocalDateTime now = now();
        UserAccount user = users.findById(principal.id()).orElseThrow(AuthService::sessionExpired);
        if (!passwordMatches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG42");
        }
        if (!PasswordPolicy.isAcceptable(request.newPassword(), user.getUsername())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG43",
                    PasswordPolicy.MIN_LENGTH, PasswordPolicy.MAX_LENGTH);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG44");
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()), now);
        sessions.revokeOtherSessions(user.getId(), principal.sessionId(), now, AuthSession.RevokeReason.PASSWORD_CHANGED);
    }

    /**
     * Wrong password or temporary lock: throws after counting the attempt. Unknown usernames go
     * through the same steps with an unsaved account, which is not audited (audit_log needs an id).
     */
    private void checkPassword(UserAccount account, String password, LocalDateTime now, String ipAddress) {
        // While temporarily locked the password is not even checked, so guessing cannot continue.
        if (account.isTemporarilyLocked(now)) {
            throw new BusinessException(HttpStatus.LOCKED, "MSG11", minutesUntil(now, account.getLockedUntil()));
        }
        if (passwordMatches(password, account.getPasswordHash())) {
            return;
        }
        boolean lockedNow = account.registerFailedLogin(now, properties.maxFailedAttempts(), properties.lockDuration());
        if (account.getId() != null) {
            audit(account, "LOGIN_FAILED", ipAddress, now);
            if (lockedNow) {
                audit(account, "ACCOUNT_TEMP_LOCKED", ipAddress, now);
            }
        }
        if (lockedNow) {
            throw new BusinessException(HttpStatus.LOCKED, "MSG12",
                    properties.maxFailedAttempts(), properties.lockDuration().toMinutes());
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "MSG09");
    }

    private UserAccount unknownUser(String username, LocalDateTime now) {
        if (unknownUsers.size() >= MAX_UNKNOWN_USERNAMES) {
            unknownUsers.clear();
        }
        return unknownUsers.computeIfAbsent(username,
                u -> UserAccount.create(u, dummyPasswordHash, u, Role.OWNER, null, null, now));
    }

    /**
     * A token that was already rotated away came back: someone kept a copy. Past the grace period
     * the whole session is ended, so neither the thief nor the owner can keep refreshing with it.
     */
    private void revokeIfReused(String hash, LocalDateTime now) {
        sessions.findByPreviousRefreshTokenHashForUpdate(hash)
                .filter(s -> s.isActive(now) && now.isAfter(s.getLastRefreshedAt().plus(REUSE_GRACE)))
                .ifPresent(s -> {
                    s.revoke(now, AuthSession.RevokeReason.REFRESH_TOKEN_REUSED);
                    audit(s.getUser(), "REFRESH_TOKEN_REUSED", s.getIpAddress(), now);
                });
    }

    private void audit(UserAccount user, String action, String ipAddress, LocalDateTime now) {
        auditLog.save(AuditLog.record("USER_ACCOUNT", user.getId(), action, user.getId(), "IP " + ipAddress, now));
    }

    private IssuedTokens issue(UserAccount user, AuthSession session, String refreshToken) {
        String accessToken = jwtService.issueAccessToken(user.getId(), session.getId());
        CurrentUserResponse me = CurrentUserResponse.from(toPrincipal(user, session.getId()));
        AuthResponse body = AuthResponse.bearer(accessToken, properties.accessTokenTtl().toSeconds(), me);
        return new IssuedTokens(body, refreshToken);
    }

    private AuthenticatedUser toPrincipal(UserAccount user, long sessionId) {
        return new AuthenticatedUser(user.getId(), sessionId, user.getUsername(), user.getFullName(),
                user.getRole(), user.getStoreId(), Set.copyOf(user.permissions()), user.isMustChangePassword());
    }

    private boolean passwordMatches(String rawPassword, String passwordHash) {
        return PasswordPolicy.fitsBcrypt(rawPassword) && passwordEncoder.matches(rawPassword, passwordHash);
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private static long minutesUntil(LocalDateTime now, LocalDateTime until) {
        long seconds = Duration.between(now, until).toSeconds();
        return Math.max(1, (seconds + 59) / 60);
    }

    private static BusinessException sessionExpired() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "MSG06");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
