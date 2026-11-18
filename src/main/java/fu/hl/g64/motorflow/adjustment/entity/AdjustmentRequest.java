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

    @Column(name = "goods_receipt_note_id")
    private Long goodsReceiptNoteId;

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

    /**
     * A request submitted for Owner's decision (UC-81 step 7, UC-83 step 7): PENDING from the start, as nothing is
     * saved as a draft (plan 6.6). At most one source document; a correction names exactly one.
     */
    public static AdjustmentRequest submit(AdjustmentType type, long storeId, Source source, String reason,
                                           long requestedBy, LocalDateTime now) {
        AdjustmentRequest request = new AdjustmentRequest();
        request.adjustmentType = type;
        request.storeId = storeId;
        if (source != null) {
            switch (source.type()) {
                case SALES_ORDER -> request.salesOrderId = source.id();
                case PAYMENT -> request.paymentId = source.id();
                case GOODS_RECEIPT_NOTE -> request.goodsReceiptNoteId = source.id();
                case TRANSFER_ORDER -> request.transferOrderId = source.id();
                case RECEIVABLE -> request.receivableId = source.id();
            }
        }
        request.reason = reason;
        request.status = AdjustmentRequestStatus.PENDING;
        request.requestedBy = requestedBy;
        request.createdAt = now;
        request.submittedAt = now;
        return request;
    }

    /** The document a request comes from. */
    public record Source(SourceType type, long id) {
        public enum SourceType { SALES_ORDER, PAYMENT, GOODS_RECEIPT_NOTE, TRANSFER_ORDER, RECEIVABLE }
    }

    /** The source document, or null for a loss found in the warehouse. */
    public Source source() {
        if (salesOrderId != null) {
            return new Source(Source.SourceType.SALES_ORDER, salesOrderId);
        }
        if (paymentId != null) {
            return new Source(Source.SourceType.PAYMENT, paymentId);
        }
        if (goodsReceiptNoteId != null) {
            return new Source(Source.SourceType.GOODS_RECEIPT_NOTE, goodsReceiptNoteId);
        }
        if (transferOrderId != null) {
            return new Source(Source.SourceType.TRANSFER_ORDER, transferOrderId);
        }
        if (receivableId != null) {
            return new Source(Source.SourceType.RECEIVABLE, receivableId);
        }
        return null;
    }

    public boolean isRequestedBy(long userId) {
        return requestedBy == userId;
    }

    /** UC-84 step 7: Owner's approval, recorded before the request is applied. */
    public void approve(long ownerId, LocalDateTime now) {
        require(AdjustmentRequestStatus.PENDING);
        status = AdjustmentRequestStatus.APPROVED;
        decidedBy = ownerId;
        decidedAt = now;
    }

    /** UC-84 Step 3_Reject request. */
    public void reject(long ownerId, String reason, LocalDateTime now) {
        require(AdjustmentRequestStatus.PENDING);
        status = AdjustmentRequestStatus.REJECTED;
        decidedBy = ownerId;
        decidedAt = now;
        decisionReason = reason;
    }

    /** The requester takes back a request Owner has not decided on yet (plan 6.6). */
    public void withdraw() {
        require(AdjustmentRequestStatus.PENDING);
        status = AdjustmentRequestStatus.WITHDRAWN;
    }

    /** UC-84 step 9: applied once, on the day of application (BR-65). */
    public void markApplied(LocalDateTime now) {
        require(AdjustmentRequestStatus.APPROVED);
        status = AdjustmentRequestStatus.APPLIED;
        appliedAt = now;
    }

    private void require(AdjustmentRequestStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("adjustment request " + id + " is " + status + ", not " + expected);
        }
    }
}
