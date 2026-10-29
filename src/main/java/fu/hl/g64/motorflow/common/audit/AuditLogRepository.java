package fu.hl.g64.motorflow.common.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(String entityType, Long entityId,
                                                                                       Collection<String> actions);
}
