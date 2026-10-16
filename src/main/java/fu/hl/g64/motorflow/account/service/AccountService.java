package fu.hl.g64.motorflow.account.service;

import fu.hl.g64.motorflow.account.dto.AccountResponse;
import fu.hl.g64.motorflow.account.dto.CreateAccountRequest;
import fu.hl.g64.motorflow.account.dto.ResetPasswordRequest;
import fu.hl.g64.motorflow.account.entity.PermissionHistory;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.PermissionHistoryRepository;
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

/** UC-08 Create Account and UC-11 Reset Password. Admin only (BR-01). */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserAccountRepository users;
    private final StoreRepository stores;
    private final PermissionHistoryRepository permissionHistory;
    private final AuthSessionRepository sessions;
    private final AuditLogRepository auditLog;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional
    public AccountResponse createAccount(AuthenticatedUser admin, CreateAccountRequest request) {
        String username = UserAccount.normalizeUsername(request.username());
        Role role = request.role();
        // BR-02: Branch Manager and Staff work at exactly one store; Owner and Admin at none.
        if (role.requiresStore() != (request.storeId() != null)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG16");
        }
        if (request.storeId() != null) {
            stores.findById(request.storeId())
                    .filter(Store::acceptsStaff)
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG46"));
        }
        requireAcceptablePassword(request.temporaryPassword(), username);
        if (users.existsByUsername(username)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG45", username);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        UserAccount account = UserAccount.create(username, passwordEncoder.encode(request.temporaryPassword()),
                request.fullName().strip(), role, request.storeId(), admin.id(), now);
        account.updateContactDetails(blankToNull(request.phone()), blankToNull(request.email()), now);
        try {
            account = users.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            // Another Admin created the same username a moment earlier.
            throw new BusinessException(HttpStatus.CONFLICT, "MSG45", username);
        }
        permissionHistory.save(PermissionHistory.accountCreated(account, admin.id(), null, now));
        return AccountResponse.from(account);
    }

    /**
     * The user must change the new temporary password at the next sign-in, and every device they
     * are signed in on is signed out, because whoever knew the old password must lose access.
     */
    @Transactional
    public void resetPassword(AuthenticatedUser admin, long accountId, ResetPasswordRequest request) {
        if (accountId == admin.id()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG47");
        }
        UserAccount account = users.findById(accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG48"));
        requireAcceptablePassword(request.temporaryPassword(), account.getUsername());

        LocalDateTime now = LocalDateTime.now(clock);
        account.resetPassword(passwordEncoder.encode(request.temporaryPassword()), now);
        sessions.revokeAllSessions(account.getId(), now, AuthSession.RevokeReason.PASSWORD_RESET);
        auditLog.save(AuditLog.record("USER_ACCOUNT", account.getId(), "PASSWORD_RESET", admin.id(),
                blankToNull(request.reason()), now));
    }

    private static void requireAcceptablePassword(String password, String username) {
        if (!PasswordPolicy.isAcceptable(password, username)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG43",
                    PasswordPolicy.MIN_LENGTH, PasswordPolicy.MAX_LENGTH);
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
