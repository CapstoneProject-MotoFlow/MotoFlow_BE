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

/** Assignment of a picking, inspection, dispatch-preparation or counting task to one Staff member (BR-32, UC-88). */
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

    @Column(name = "goods_receipt_id")
    private Long goodsReceiptId;

    @Column(name = "transfer_order_id")
    private Long transferOrderId;

    @Column(name = "stock_count_id")
    private Long stockCountId;

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

    /** The document goes back to waiting for assignment, for example when the assignee moves away (UC-09). */
    public void cancel() {
        status = WarehouseTaskStatus.CANCELLED;
    }
}
