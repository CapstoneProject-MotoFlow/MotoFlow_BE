package fu.hl.g64.motorflow.account.dto;

import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.auth.entity.AuthSession;
import fu.hl.g64.motorflow.auth.service.UserAgents;

import java.time.LocalDateTime;
import java.util.Set;

/** An account as Admin sees it. Never contains the password or its hash. */
public record AccountResponse(long id, String username, String fullName, String phone, String email, Role role,
                              Long storeId, String storeName, AccountStatus status, AccountState state,
                              Set<PermissionCode> permissions, boolean mustChangePassword,
                              LocalDateTime lastLoginAt, LastSession lastSession, LocalDateTime createdAt) {

    /** The newest sign-in: where from, on what, and whether it can still be used. Null if the user never signed in. */
    public record LastSession(LocalDateTime signedInAt, String ipAddress, String device, SessionStatus status) {

        public enum SessionStatus { ACTIVE, SIGNED_OUT, EXPIRED }

        static LastSession from(AuthSession session, LocalDateTime now) {
            if (session == null) {
                return null;
            }
            SessionStatus status = session.getRevokedAt() != null ? SessionStatus.SIGNED_OUT
                    : session.isActive(now) ? SessionStatus.ACTIVE : SessionStatus.EXPIRED;
            return new LastSession(session.getCreatedAt(), session.getIpAddress(),
                    UserAgents.describe(session.getUserAgent()), status);
        }
    }

    public static AccountResponse from(UserAccount account, String storeName, LocalDateTime now) {
        return from(account, storeName, null, now);
    }

    public static AccountResponse from(UserAccount account, String storeName, AuthSession lastSession, LocalDateTime now) {
        AccountState state = account.isLockedByAdmin() ? AccountState.LOCKED
                : account.isTemporarilyLocked(now) ? AccountState.TEMPORARILY_LOCKED
                : AccountState.ACTIVE;
        return new AccountResponse(account.getId(), account.getUsername(), account.getFullName(), account.getPhone(),
                account.getEmail(), account.getRole(), account.getStoreId(), storeName, account.getStatus(), state,
                account.permissions(), account.isMustChangePassword(), account.getLastLoginAt(),
                LastSession.from(lastSession, now), account.getCreatedAt());
    }
}
