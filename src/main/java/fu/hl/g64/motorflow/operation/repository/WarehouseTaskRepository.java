package fu.hl.g64.motorflow.operation.repository;

import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface WarehouseTaskRepository extends JpaRepository<WarehouseTask, Long> {

    List<WarehouseTask> findByAssignedToAndStatus(Long assignedTo, WarehouseTaskStatus status);

    /** [taskType, number of tasks] given to the Staff member and not started on yet. */
    @Query("""
            select t.taskType, count(t) from WarehouseTask t
            where t.assignedTo = :staffId
              and t.status = fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.ASSIGNED
            group by t.taskType""")
    List<Object[]> countAssignedByType(Long staffId);
}
