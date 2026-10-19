package fu.hl.g64.motorflow.account.entity;

import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_code", nullable = false, length = 30)
    private PermissionCode permissionCode;

    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    public static UserPermission grant(Long userId, PermissionCode code, Long grantedBy, LocalDateTime now) {
        UserPermission permission = new UserPermission();
        permission.userId = userId;
        permission.permissionCode = code;
        permission.grantedBy = grantedBy;
        permission.grantedAt = now;
        return permission;
    }
}
