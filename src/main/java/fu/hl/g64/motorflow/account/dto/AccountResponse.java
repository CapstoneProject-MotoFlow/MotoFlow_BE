package fu.hl.g64.motorflow.account.dto;

import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;

import java.time.LocalDateTime;
import java.util.Set;

/** An account as Admin sees it. Never contains the password or its hash. */
public record AccountResponse(long id, String username, String fullName, String phone, String email, Role role,
                              Long storeId, String storeName, AccountStatus status, AccountState state,
                              Set<PermissionCode> permissions, boolean mustChangePassword,
                              LocalDateTime lastLoginAt, LocalDateTime createdAt) {

    public static AccountResponse from(UserAccount account, String storeName, Set<PermissionCode> permissions,
                                       LocalDateTime now) {
        AccountState state = account.isLockedByAdmin() ? AccountState.LOCKED
                : account.isTemporarilyLocked(now) ? AccountState.TEMPORARILY_LOCKED
                : AccountState.ACTIVE;
        return new AccountResponse(account.getId(), account.getUsername(), account.getFullName(), account.getPhone(),
                account.getEmail(), account.getRole(), account.getStoreId(), storeName, account.getStatus(), state,
                permissions, account.isMustChangePassword(), account.getLastLoginAt(), account.getCreatedAt());
    }
}
