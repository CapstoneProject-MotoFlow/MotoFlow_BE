package fu.hl.g64.motorflow.auth.service;

import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.account.repository.UserPermissionRepository;
import fu.hl.g64.motorflow.auth.config.AuthProperties;
import fu.hl.g64.motorflow.auth.dto.AuthResponse;
import fu.hl.g64.motorflow.auth.dto.ChangePasswordRequest;
import fu.hl.g64.motorflow.auth.dto.CurrentUserResponse;
import fu.hl.g64.motorflow.auth.dto.LoginRequest;
import fu.hl.g64.motorflow.auth.entity.AuthSession;
import fu.hl.g64.motorflow.auth.repository.AuthSessionRepository;
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
import java.util.Set;
import java.util.UUID;

/** UC-01 Log In and UC-02 Change Password, plus the session lifecycle behind them. */
@Service
public class AuthService {

    private final UserAccountRepository users;
    private final UserPermissionRepository permissions;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties properties;
    private final Clock clock;
    /** Checked against when the username does not exist, so both cases take the same time. */
    private final String dummyPasswordHash;

    public AuthService(UserAccountRepository users, UserPermissionRepository permissions,
                       AuthSessionRepository sessions, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuthProperties properties, Clock clock) {
        this.users = users;
        this.permissions = permissions;
        this.sessions = sessions;
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
            passwordEncoder.matches(password, dummyPasswordHash);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "MSG09");
        }
        // While temporarily locked the password is not even checked, so guessing cannot continue.
        if (user.isTemporarilyLocked(now)) {
            throw new BusinessException(HttpStatus.LOCKED, "MSG11", minutesUntil(now, user.getLockedUntil()));
        }
        if (!passwordMatches(password, user.getPasswordHash())) {
            boolean lockedNow = user.registerFailedLogin(now, properties.maxFailedAttempts(), properties.lockDuration());
            if (lockedNow) {
                throw new BusinessException(HttpStatus.LOCKED, "MSG12",
                        properties.maxFailedAttempts(), properties.lockDuration().toMinutes());
            }
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "MSG09");
        }
        // Checked after the password so that a stranger cannot learn which accounts exist and are locked.
        if (user.isLockedByAdmin()) {
            throw new BusinessException(HttpStatus.LOCKED, "MSG10");
        }

        user.registerSuccessfulLogin(now);
        String refreshToken = RefreshTokens.generate();
        AuthSession session = sessions.save(AuthSession.open(user, RefreshTokens.hash(refreshToken), now,
                now.plus(properties.refreshTokenTtl()), userAgent, ipAddress));
        return issue(user, session, refreshToken);
    }

    /** Gives a new access token and rotates the refresh token. */
    @Transactional(noRollbackFor = BusinessException.class)
    public IssuedTokens refresh(String refreshToken) {
        LocalDateTime now = now();
        AuthSession session = sessions.findByRefreshTokenHashForUpdate(RefreshTokens.hash(refreshToken))
                .filter(s -> s.isActive(now))
                .orElseThrow(AuthService::sessionExpired);
        UserAccount user = session.getUser();
        if (user.isLockedByAdmin()) {
            session.revoke(now, AuthSession.RevokeReason.ACCOUNT_LOCKED);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "MSG10");
        }
        String newRefreshToken = RefreshTokens.generate();
        session.rotate(RefreshTokens.hash(newRefreshToken), now, now.plus(properties.refreshTokenTtl()));
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

    private IssuedTokens issue(UserAccount user, AuthSession session, String refreshToken) {
        String accessToken = jwtService.issueAccessToken(user.getId(), session.getId());
        CurrentUserResponse me = CurrentUserResponse.from(toPrincipal(user, session.getId()));
        AuthResponse body = AuthResponse.bearer(accessToken, properties.accessTokenTtl().toSeconds(), me);
        return new IssuedTokens(body, refreshToken);
    }

    private AuthenticatedUser toPrincipal(UserAccount user, long sessionId) {
        Set<PermissionCode> codes = Set.copyOf(permissions.findCodesByUserId(user.getId()));
        return new AuthenticatedUser(user.getId(), sessionId, user.getUsername(), user.getFullName(),
                user.getRole(), user.getStoreId(), codes, user.isMustChangePassword());
    }

    private boolean passwordMatches(String rawPassword, String passwordHash) {
        return PasswordPolicy.fitsBcrypt(rawPassword) && passwordEncoder.matches(rawPassword, passwordHash);
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
