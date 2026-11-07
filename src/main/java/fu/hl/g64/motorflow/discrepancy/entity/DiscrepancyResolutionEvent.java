package fu.hl.g64.motorflow.discrepancy.entity;

import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
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

/** One action taken to resolve a discrepancy case. */
@Entity
@Table(name = "discrepancy_resolution_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscrepancyResolutionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discrepancy_case_id", nullable = false)
    private Long discrepancyCaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", nullable = false, length = 30)
    private ResolutionType resolutionType;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "goods_receipt_note_id")
    private Long goodsReceiptNoteId;

    @Column(name = "adjustment_request_id")
    private Long adjustmentRequestId;

    @Column
    private String reason;

    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /**
     * An action that is final when it is recorded (goods moved to awaiting return, returned to the supplier, a
     * replacement waived by agreement): recorded and confirmed by the same person at the same time.
     */
    public static DiscrepancyResolutionEvent done(long caseId, ResolutionType type, int quantity, String reason,
                                                  long by, LocalDateTime now) {
        DiscrepancyResolutionEvent event = pending(caseId, type, quantity, null, reason, by, now);
        event.confirmedBy = by;
        event.confirmedAt = now;
        return event;
    }

    /**
     * UC-106 Step 1_Supplier replacement: the replacement delivery {@code goodsReceiptNoteId} is expected to bring
     * {@code quantity} for the case. It settles the case only when that GRN is confirmed ({@link #confirm}).
     */
    public static DiscrepancyResolutionEvent pending(long caseId, ResolutionType type, int quantity,
                                                     Long goodsReceiptNoteId, String reason, long by,
                                                     LocalDateTime occurredAt) {
        DiscrepancyResolutionEvent event = new DiscrepancyResolutionEvent();
        event.discrepancyCaseId = caseId;
        event.resolutionType = type;
        event.quantity = quantity;
        event.goodsReceiptNoteId = goodsReceiptNoteId;
        event.reason = reason;
        event.recordedBy = by;
        event.occurredAt = occurredAt;
        return event;
    }

    /**
     * UC-76 Step 3_Loss or damage: transfer goods to be written off by the STOCK_LOSS adjustment
     * {@code adjustmentRequestId}; they count as lost only once it is applied (BR-66).
     */
    public static DiscrepancyResolutionEvent transferLoss(long caseId, int quantity, long adjustmentRequestId,
                                                          String reason, long by, LocalDateTime occurredAt) {
        DiscrepancyResolutionEvent event = pending(caseId, ResolutionType.APPROVED_LOSS, quantity, null, reason, by,
                occurredAt);
        event.adjustmentRequestId = adjustmentRequestId;
        return event;
    }

    /**
     * UC-76 Step 3_Return to source: the coordinator decided that {@code quantity} goes back to the source. It stays
     * pending, with no GRN, until the return receipt that brings the goods is confirmed ({@link #settle}).
     */
    public static DiscrepancyResolutionEvent returnDecision(long caseId, int quantity, String reason, long by,
                                                            LocalDateTime occurredAt) {
        return pending(caseId, ResolutionType.RECEIVED_BACK_AT_SOURCE, quantity, null, reason, by, occurredAt);
    }

    /** A pending return decision settled by the confirmed return receipt {@code goodsReceiptNoteId}. */
    public void settle(long goodsReceiptNoteId, long by, LocalDateTime now) {
        this.goodsReceiptNoteId = goodsReceiptNoteId;
        confirm(quantity, by, now);
    }

    /** A pending return decision of which only {@code remaining} is still to come back (partial return). */
    public void reduceTo(int remaining) {
        if (isConfirmed() || remaining <= 0 || remaining > quantity) {
            throw new IllegalArgumentException("cannot reduce resolution event " + id + " to " + remaining);
        }
        quantity = remaining;
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    /** UC-44 Step 1_Supplier replacement GRN: only the replacements accepted at confirmation settle the case. */
    public void confirm(int acceptedQuantity, long by, LocalDateTime now) {
        if (isConfirmed()) {
            throw new IllegalStateException("resolution event " + id + " is already confirmed");
        }
        quantity = acceptedQuantity;
        confirmedBy = by;
        confirmedAt = now;
    }
}
