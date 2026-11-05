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
