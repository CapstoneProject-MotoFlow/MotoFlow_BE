package fu.hl.g64.motorflow.account.helper;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.error.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** Account rules shared by the account services: the history entries of account changes and the last-Admin check. */
public final class AccountRules {

    private AccountRules() {
    }

    public static AuditLog accountCreated(UserAccount account, Long changedBy, String reason, LocalDateTime now) {
        return history(account.getId(), "ACCOUNT_CREATED", null, null, account.getRole().name(), reason, changedBy, now);
    }

    /** Locking or demoting this Admin must leave at least one active Admin (MSG69). */
    public static void requireAnotherActiveAdmin(UserAccountRepository users, UserAccount admin) {
        if (users.findActiveAdminsForUpdate().stream().noneMatch(a -> !a.getId().equals(admin.getId()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG69");
        }
    }

    /** Same shape as the permission_history rows moved into audit_log; null values are left out. */
    public static AuditLog history(Long userId, String action, PermissionCode code, String oldValue, String newValue,
                                   String reason, Long changedBy, LocalDateTime now) {
        return AuditLog.record("USER_ACCOUNT", userId, action, changedBy, reason,
                data(code, oldValue), data(code, newValue), now);
    }

    private static Map<String, Object> data(PermissionCode code, String value) {
        Map<String, Object> data = new HashMap<>();
        if (code != null) {
            data.put("permission_code", code.name());
        }
        if (value != null) {
            data.put("value", value);
        }
        return data;
    }
}
