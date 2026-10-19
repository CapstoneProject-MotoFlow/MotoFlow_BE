package fu.hl.g64.motorflow.account.repository;

import fu.hl.g64.motorflow.account.entity.PermissionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PermissionHistoryRepository extends JpaRepository<PermissionHistory, Long> {

    List<PermissionHistory> findByUserIdOrderByChangedAtDescIdDesc(Long userId);
}
