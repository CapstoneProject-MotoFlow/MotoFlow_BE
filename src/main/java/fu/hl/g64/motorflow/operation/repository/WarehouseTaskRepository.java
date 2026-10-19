package fu.hl.g64.motorflow.operation.repository;

import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarehouseTaskRepository extends JpaRepository<WarehouseTask, Long> {

    List<WarehouseTask> findByAssignedToAndStatus(Long assignedTo, WarehouseTaskStatus status);
}
