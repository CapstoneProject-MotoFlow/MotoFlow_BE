package fu.hl.g64.motorflow.account.dto;

import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;

import java.time.LocalDateTime;

/** An account as Admin sees it. Never contains the password or its hash. */
public record AccountResponse(long id, String username, String fullName, String phone, String email, Role role,
                              Long storeId, AccountStatus status, boolean mustChangePassword,
                              LocalDateTime lastLoginAt, LocalDateTime createdAt) {

    public static AccountResponse from(UserAccount account) {
        return new AccountResponse(account.getId(), account.getUsername(), account.getFullName(), account.getPhone(),
                account.getEmail(), account.getRole(), account.getStoreId(), account.getStatus(),
                account.isMustChangePassword(), account.getLastLoginAt(), account.getCreatedAt());
    }
}
