package fu.hl.g64.motorflow.adjustment.entity;

import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;
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

/** Request, with evidence, for Owner to approve a stock write-off or the correction of a posted document (BR-65, BR-66). */
@Entity
@Table(name = "adjustment_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjustmentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 25)
    private AdjustmentType adjustmentType;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "sales_order_id")
    private Long salesOrderId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "goods_receipt_id")
    private Long goodsReceiptId;

    @Column(name = "transfer_order_id")
    private Long transferOrderId;

    @Column(name = "receivable_id")
    private Long receivableId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private AdjustmentRequestStatus status;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
}
