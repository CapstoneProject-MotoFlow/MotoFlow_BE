package fu.hl.g64.motorflow.account.entity;

import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** BR-01: every account and permission change is kept; rows are never updated or deleted. */
@Entity
@Table(name = "permission_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PermissionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "change_type", nullable = false, updatable = false, length = 30)
    private String changeType;

    @Column(name = "permission_code", updatable = false, length = 30)
    private String permissionCode;

    @Column(name = "old_value", updatable = false, length = 100)
    private String oldValue;

    @Column(name = "new_value", updatable = false, length = 100)
    private String newValue;

    @Column(updatable = false)
    private String reason;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private Long changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    public static PermissionHistory accountCreated(UserAccount account, Long changedBy, String reason, LocalDateTime now) {
        return of(account.getId(), "ACCOUNT_CREATED", null, null, account.getRole().name(), reason, changedBy, now);
    }

    public static PermissionHistory roleChanged(Long userId, Role oldRole, Role newRole, String reason, Long changedBy,
                                                LocalDateTime now) {
        return of(userId, "ROLE_CHANGED", null, oldRole.name(), newRole.name(), reason, changedBy, now);
    }

    /** Values are store codes; null means no store. */
    public static PermissionHistory storeChanged(Long userId, String oldStoreCode, String newStoreCode, String reason,
                                                 Long changedBy, LocalDateTime now) {
        return of(userId, "STORE_CHANGED", null, oldStoreCode, newStoreCode, reason, changedBy, now);
    }

    public static PermissionHistory permissionGranted(Long userId, PermissionCode code, String reason, Long changedBy,
                                                      LocalDateTime now) {
        return of(userId, "PERMISSION_GRANTED", code.name(), null, null, reason, changedBy, now);
    }

    public static PermissionHistory permissionRevoked(Long userId, PermissionCode code, String reason, Long changedBy,
                                                      LocalDateTime now) {
        return of(userId, "PERMISSION_REVOKED", code.name(), null, null, reason, changedBy, now);
    }

    private static PermissionHistory of(Long userId, String changeType, String permissionCode, String oldValue,
                                        String newValue, String reason, Long changedBy, LocalDateTime now) {
        PermissionHistory entry = new PermissionHistory();
        entry.userId = userId;
        entry.changeType = changeType;
        entry.permissionCode = permissionCode;
        entry.oldValue = oldValue;
        entry.newValue = newValue;
        entry.reason = reason;
        entry.changedBy = changedBy;
        entry.changedAt = now;
        return entry;
    }
}
