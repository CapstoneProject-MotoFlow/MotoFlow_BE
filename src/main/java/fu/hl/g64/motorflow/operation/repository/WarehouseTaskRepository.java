package fu.hl.g64.motorflow.operation.repository;

import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
                   or t.transferOrderId = :documentId or t.stockCountId = :documentId
                   or t.purchaseOrderId = :documentId)""";

    List<WarehouseTask> findByAssignedToAndStatus(Long assignedTo, WarehouseTaskStatus status);

    /** The user's tasks of one type and status, oldest assignment first. */
    List<WarehouseTask> findByAssignedToAndTaskTypeAndStatusOrderByAssignedAtAscIdAsc(Long assignedTo, TaskType taskType,
                                                                                     WarehouseTaskStatus status);

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

    /**
     * Inspections of supplier deliveries given to the user and not finished: the result is still to be recorded or
     * waits for confirmation (UC-39 for Staff, and for a Branch Manager who took the task).
     */
    @Query("""
            select t from WarehouseTask t
            where t.assignedTo = :userId
              and t.taskType = fu.hl.g64.motorflow.operation.entity.enums.TaskType.INSPECTION
              and t.status in (fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.ASSIGNED,
                               fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.RESULT_SUBMITTED)
              and t.goodsReceiptNoteId in (select r.id from GoodsReceiptNote r
                  where r.receiptType = fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.SUPPLIER_DELIVERY)""")
    Page<WarehouseTask> findOpenSupplierInspections(Long userId, Pageable pageable);

    /** The open inspection task (ASSIGNED or RESULT_SUBMITTED) of each of the given receipts that has one. */
    @Query("""
            select t from WarehouseTask t
            where t.taskType = fu.hl.g64.motorflow.operation.entity.enums.TaskType.INSPECTION
              and t.goodsReceiptNoteId in :receiptIds
              and t.status in (fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.ASSIGNED,
                               fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.RESULT_SUBMITTED)""")
    List<WarehouseTask> findOpenInspections(Collection<Long> receiptIds);

    /** The open picking task (ASSIGNED or RESULT_SUBMITTED) of each of the given sales orders that has one. */
    @Query("""
            select t from WarehouseTask t
            where t.taskType = fu.hl.g64.motorflow.operation.entity.enums.TaskType.PICKING
              and t.salesOrderId in :orderIds
              and t.status in (fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.ASSIGNED,
                               fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.RESULT_SUBMITTED)""")
    List<WarehouseTask> findOpenPickings(Collection<Long> orderIds);

    /** The latest picking task that was not cancelled, of each of the given sales orders that has one (BR-26). */
    @Query("""
            select t from WarehouseTask t
            where t.taskType = fu.hl.g64.motorflow.operation.entity.enums.TaskType.PICKING
              and t.salesOrderId in :orderIds
              and t.id = (select max(o.id) from WarehouseTask o
                          where o.taskType = fu.hl.g64.motorflow.operation.entity.enums.TaskType.PICKING
                            and o.salesOrderId = t.salesOrderId
                            and o.status <> fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.CANCELLED)""")
    List<WarehouseTask> findLatestPickings(Collection<Long> orderIds);
}
