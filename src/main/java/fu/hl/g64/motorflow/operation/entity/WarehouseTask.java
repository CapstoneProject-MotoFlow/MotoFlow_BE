package fu.hl.g64.motorflow.operation.entity;

import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
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

/**
 * Assignment of a picking, inspection, dispatch-preparation or counting task to one person (BR-32, UC-86): a Staff
 * member, or a Branch Manager who took it themself (MSG40).
 * A document has at most one open task (ASSIGNED or RESULT_SUBMITTED); giving it to someone else cancels the open task
 * and adds a new one, so the rows of a document are its assignment history.
 */
@Entity
@Table(name = "warehouse_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 25)
    private TaskType taskType;

    @Column(name = "sales_order_id")
    private Long salesOrderId;

    @Column(name = "goods_receipt_note_id")
    private Long goodsReceiptNoteId;

    @Column(name = "transfer_order_id")
    private Long transferOrderId;

    @Column(name = "stock_count_id")
    private Long stockCountId;

    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    @Column(name = "assigned_to", nullable = false)
    private Long assignedTo;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WarehouseTaskStatus status;

    @Column(name = "result_submitted_at")
    private LocalDateTime resultSubmittedAt;

    /**
     * UC-86: gives the task of one document to a Staff member, or to the Branch Manager who takes it themself;
     * the task type decides which document column is set.
     */
    public static WarehouseTask assign(TaskType type, long documentId, long assigneeId, long assignedBy,
                                       LocalDateTime now) {
        WarehouseTask task = new WarehouseTask();
        task.taskType = type;
        switch (type) {
            case PICKING -> task.salesOrderId = documentId;
            case INSPECTION -> task.goodsReceiptNoteId = documentId;
            case DISPATCH_PREPARATION, TRANSFER_RECEIVING -> task.transferOrderId = documentId;
            case COUNTING -> task.stockCountId = documentId;
            case SUPPLIER_RECEIVING -> task.purchaseOrderId = documentId;
        }
        task.assignedTo = assigneeId;
        task.assignedBy = assignedBy;
        task.assignedAt = now;
        task.status = WarehouseTaskStatus.ASSIGNED;
        return task;
    }

    /** The assignee has sent the result for confirmation; from now on the task cannot be given to someone else. */
    public void submitResult(LocalDateTime now) {
        requireStatus(WarehouseTaskStatus.ASSIGNED);
        status = WarehouseTaskStatus.RESULT_SUBMITTED;
        resultSubmittedAt = now;
    }

    /**
     * The result was sent back for a recount, before or after it was confirmed (an Owner can still ask for a recount
     * of a confirmed stock count): the same Staff member records again and stays the recorder.
     */
    public void reopen() {
        if (status != WarehouseTaskStatus.RESULT_SUBMITTED && status != WarehouseTaskStatus.DONE) {
            throw new IllegalStateException("task " + id + " is " + status + ", expected RESULT_SUBMITTED or DONE");
        }
        status = WarehouseTaskStatus.ASSIGNED;
        resultSubmittedAt = null;
    }

    /** The assignee recorded the goods receipt note of a receiving task; its inspection is a task of its own. */
    public void finishReceiving() {
        if (!taskType.isReceiving()) {
            throw new IllegalStateException("task " + id + " is a " + taskType + " task, not a receiving task");
        }
        requireStatus(WarehouseTaskStatus.ASSIGNED);
        status = WarehouseTaskStatus.DONE;
    }

    /** The result was confirmed. */
    public void complete() {
        requireStatus(WarehouseTaskStatus.RESULT_SUBMITTED);
        status = WarehouseTaskStatus.DONE;
    }

    /**
     * The document goes back to waiting for assignment, for example when the assignee moves away (UC-09) or the
     * Branch Manager gives it to someone else (UC-86), or the document itself is cancelled.
     */
    public void cancel() {
        status = WarehouseTaskStatus.CANCELLED;
    }

    private void requireStatus(WarehouseTaskStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("task " + id + " is " + status + ", expected " + expected);
        }
    }
}
