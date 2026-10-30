package fu.hl.g64.motorflow.account.service;

import fu.hl.g64.motorflow.account.dto.AccountResponse;
import fu.hl.g64.motorflow.account.dto.AccountState;
import fu.hl.g64.motorflow.account.dto.CreateAccountRequest;
import fu.hl.g64.motorflow.account.dto.CreatedAccountResponse;
import fu.hl.g64.motorflow.account.dto.TemporaryPasswordResponse;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.auth.entity.AuthSession;
import fu.hl.g64.motorflow.auth.repository.AuthSessionRepository;
import fu.hl.g64.motorflow.auth.service.PasswordPolicy;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** UC-07 View Account List, UC-08 Create Account, UC-10 Lock/Unlock Account, UC-11 Reset Password. Admin only (BR-01). */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserAccountRepository users;
    private final StoreRepository stores;
    private final AuthSessionRepository sessions;
    private final AuditLogRepository auditLog;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * Every filter is optional; {@code q} matches full name, username, email or store name.
     * Most recent sign-in first; accounts that never signed in come last.
     * shortcut: filters in memory, fine for a chain's few hundred accounts; move to a query if they reach thousands.
     */
    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts(String q, Role role, Long storeId, AccountState state) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, String> storeNames = stores.findAll().stream()
                .collect(Collectors.toMap(Store::getId, Store::getStoreName));
        String needle = StringUtils.hasText(q) ? q.strip().toLowerCase(Locale.ROOT) : null;
        Map<Long, AuthSession> lastSessions = sessions.findLatestPerUser().stream()
                .collect(Collectors.toMap(s -> s.getUser().getId(), s -> s));

        return users.findAll().stream()
                .map(account -> AccountResponse.from(account, storeNames.get(account.getStoreId()),
                        lastSessions.get(account.getId()), now))
                .filter(a -> role == null || a.role() == role)
                .filter(a -> storeId == null || storeId.equals(a.storeId()))
                .filter(a -> state == null || a.state() == state)
                .filter(a -> needle == null || Stream.of(a.fullName(), a.username(), a.email(), a.storeName())
                        .anyMatch(text -> text != null && text.toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(Comparator.comparing(AccountResponse::lastLoginAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AccountResponse::id))
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(long accountId) {
        UserAccount account = users.findById(accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG48"));
        String storeName = account.getStoreId() == null ? null
                : stores.findById(account.getStoreId()).map(Store::getStoreName).orElse(null);
        AuthSession lastSession = sessions.findFirstByUserIdOrderByIdDesc(accountId).orElse(null);
        return AccountResponse.from(account, storeName, lastSession, LocalDateTime.now(clock));
    }

    @Transactional
    public CreatedAccountResponse createAccount(AuthenticatedUser admin, CreateAccountRequest request) {
        String username = UserAccount.normalizeUsername(request.username());
        Role role = request.role();
        // BR-02: Branch Manager and Staff work at exactly one store; Owner and Admin at none.
        if (role.requiresStore() != (request.storeId() != null)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG16");
        }
        Store store = null;
        if (request.storeId() != null) {
            store = stores.findById(request.storeId())
                    .filter(Store::acceptsStaff)
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG46"));
        }
        if (users.existsByUsername(username)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG45", username);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String temporaryPassword = PasswordPolicy.randomTemporary(username);
        UserAccount account = UserAccount.create(username, passwordEncoder.encode(temporaryPassword),
                request.fullName().strip(), role, request.storeId(), admin.id(), now);
        account.updateContactDetails(blankToNull(request.phone()), blankToNull(request.email()), now);
        try {
            account = users.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            // Another Admin created the same username a moment earlier.
            throw new BusinessException(HttpStatus.CONFLICT, "MSG45", username);
        }
        auditLog.save(PermissionService.accountCreated(account, admin.id(), null, now));
        return new CreatedAccountResponse(
                AccountResponse.from(account, store == null ? null : store.getStoreName(), now),
                temporaryPassword);
    }

    /** UC-10. The user is signed out on every device at once. Locking a locked account changes nothing. */
    @Transactional
    public void lockAccount(AuthenticatedUser admin, long accountId, String reason) {
        UserAccount account = otherAccount(admin, accountId);
        if (account.isLockedByAdmin()) {
            return;
        }
        if (account.getRole() == Role.ADMIN) {
            requireAnotherActiveAdmin(users, account);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        account.lockByAdmin(now);
        sessions.revokeAllSessions(account.getId(), now, AuthSession.RevokeReason.ACCOUNT_LOCKED);
        auditLog.save(AuditLog.record("USER_ACCOUNT", account.getId(), "ACCOUNT_LOCKED", admin.id(),
                blankToNull(reason), now));
    }

    /** UC-10. Lifts an Admin lock and the temporary lock after wrong passwords alike. */
    @Transactional
    public void unlockAccount(AuthenticatedUser admin, long accountId, String reason) {
        UserAccount account = otherAccount(admin, accountId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!account.isLockedByAdmin() && !account.isTemporarilyLocked(now)) {
            return;
        }
        account.unlockByAdmin(now);
        auditLog.save(AuditLog.record("USER_ACCOUNT", account.getId(), "ACCOUNT_UNLOCKED", admin.id(),
                blankToNull(reason), now));
    }

    /**
     * The user must change the new temporary password at the next sign-in, and every device they
     * are signed in on is signed out, because whoever knew the old password must lose access.
     */
    @Transactional
    public TemporaryPasswordResponse resetPassword(AuthenticatedUser admin, long accountId, String reason) {
        if (accountId == admin.id()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG47");
        }
        UserAccount account = users.findById(accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG48"));
        LocalDateTime now = LocalDateTime.now(clock);
        String temporaryPassword = PasswordPolicy.randomTemporary(account.getUsername());
        account.resetPassword(passwordEncoder.encode(temporaryPassword), now);
        sessions.revokeAllSessions(account.getId(), now, AuthSession.RevokeReason.PASSWORD_RESET);
        auditLog.save(AuditLog.record("USER_ACCOUNT", account.getId(), "PASSWORD_RESET", admin.id(),
                blankToNull(reason), now));
        return new TemporaryPasswordResponse(temporaryPassword);
    }

    /** Admin cannot lock themself out (MSG15). */
    private UserAccount otherAccount(AuthenticatedUser admin, long accountId) {
        if (accountId == admin.id()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG15");
        }
        return users.findById(accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG48"));
    }

    /** Locking or demoting this Admin must leave at least one active Admin (MSG69). */
    static void requireAnotherActiveAdmin(UserAccountRepository users, UserAccount admin) {
        if (users.findActiveAdminsForUpdate().stream().noneMatch(a -> !a.getId().equals(admin.getId()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG69");
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
