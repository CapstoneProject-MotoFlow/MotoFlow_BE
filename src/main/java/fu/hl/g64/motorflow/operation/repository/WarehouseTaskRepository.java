package fu.hl.g64.motorflow.operation.repository;

import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WarehouseTaskRepository extends JpaRepository<WarehouseTask, Long> {

    /** The task type decides which document column is set, so matching any of them finds the document's tasks. */
    String OF_DOCUMENT = """
            t.taskType = :type
              and (t.salesOrderId = :documentId or t.goodsReceiptNoteId = :documentId
                   or t.transferOrderId = :documentId or t.stockCountId = :documentId)""";

    List<WarehouseTask> findByAssignedToAndStatus(Long assignedTo, WarehouseTaskStatus status);

    /** [taskType, number of tasks] given to the Staff member and not started on yet. */
    @Query("""
            select t.taskType, count(t) from WarehouseTask t
            where t.assignedTo = :staffId
              and t.status = fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.ASSIGNED
            group by t.taskType""")
    List<Object[]> countAssignedByType(Long staffId);

    /**
     * The document's open task (ASSIGNED or RESULT_SUBMITTED; the database allows at most one), locked so that a
     * reassignment and the assignee's own submission cannot overwrite each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WarehouseTask t where " + OF_DOCUMENT + " and t.status in ("
            + "fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.ASSIGNED, "
            + "fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.RESULT_SUBMITTED)")
    Optional<WarehouseTask> findOpenForUpdate(TaskType type, Long documentId);

    /** Every assignment of the document, newest first (UC-86 assignment history). */
    @Query("select t from WarehouseTask t where " + OF_DOCUMENT + " order by t.assignedAt desc, t.id desc")
    List<WarehouseTask> findHistory(TaskType type, Long documentId);

    /** [assignedTo, number of tasks of this type they hold and have not recorded yet], for the listed accounts. */
    @Query("""
            select t.assignedTo, count(t) from WarehouseTask t
            where t.taskType = :type and t.assignedTo in :assigneeIds
              and t.status = fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.ASSIGNED
            group by t.assignedTo""")
    List<Object[]> countAssignedByAssignee(TaskType type, Collection<Long> assigneeIds);
}
