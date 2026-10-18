package fu.hl.g64.motorflow.account.repository;

import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Set;

public interface UserPermissionRepository extends JpaRepository<UserPermission, Long> {

    @Query("select p.permissionCode from UserPermission p where p.userId = :userId")
    Set<PermissionCode> findCodesByUserId(Long userId);

    List<UserPermission> findByUserId(Long userId);
}
