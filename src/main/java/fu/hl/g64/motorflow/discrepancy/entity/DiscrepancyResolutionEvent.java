package fu.hl.g64.motorflow.discrepancy.entity;

import fu.hl.g64.motorflow.discrepancy.entity.enums.GoodsKind;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.WrongItemReceiptStatus;
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

import java.time.LocalDate;
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

    /** Góp ý UI 21/11 (V21): on a return to the supplier, the goods it handed back; null on older returns. */
    @Enumerated(EnumType.STRING)
    @Column(name = "goods_kind", length = 15)
    private GoodsKind goodsKind;

    @Column(name = "goods_receipt_note_id")
    private Long goodsReceiptNoteId;

    /** Plan 23/11 (V23): on a transfer phiếu A, the part the wrong item really is and the expiry the BM recorded. */
    @Column(name = "received_product_id")
    private Long receivedProductId;

    @Column(name = "received_expiry_date")
    private LocalDate receivedExpiryDate;

    /** PM 23/11 (V23): on a transfer's phiếu nhận hàng giao sai only. */
    @Enumerated(EnumType.STRING)
    @Column(length = 15)
    private WrongItemReceiptStatus status;

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

    /** UC-46 Step 1_Actual supplier return of {@code quantity} goods of one kind. */
    public static DiscrepancyResolutionEvent returned(long caseId, GoodsKind kind, int quantity, String reason,
                                                      long by, LocalDateTime now) {
        DiscrepancyResolutionEvent event = done(caseId, ResolutionType.RETURNED_TO_SUPPLIER, quantity, reason, by, now);
        event.goodsKind = kind;
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
     * UC-84 step 7: held goods of a supplier delivery that an applied STOCK_LOSS request {@code adjustmentRequestId}
     * wrote off (UC-46 Step 3_Goods cannot be returned). Final when recorded, as the stock has left.
     */
    public static DiscrepancyResolutionEvent heldWriteOff(long caseId, int quantity, long adjustmentRequestId,
                                                          String reason, long by, LocalDateTime now) {
        DiscrepancyResolutionEvent event = done(caseId, ResolutionType.APPROVED_LOSS, quantity, reason, by, now);
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

    /**
     * PM 23/11 (phiếu nhận hàng giao sai): the destination's BM recorded that {@code quantity} wrong items received with
     * the case's part are really {@code receivedProductId}. Nothing moves until the Owner confirms it
     * ({@link #confirmWrongItemReceipt}); there is no rejection.
     */
    public static DiscrepancyResolutionEvent wrongItemReceipt(long caseId, int quantity, long receivedProductId,
                                                              LocalDate expiryDate, String reason, long by,
                                                              LocalDateTime occurredAt) {
        DiscrepancyResolutionEvent event = pending(caseId, ResolutionType.RECEIVED_AS_OTHER_PRODUCT, quantity, null,
                reason, by, occurredAt);
        event.receivedProductId = receivedProductId;
        event.receivedExpiryDate = expiryDate;
        event.status = WrongItemReceiptStatus.AWAITING_OWNER;
        return event;
    }

    public void confirmWrongItemReceipt(long by, LocalDateTime now) {
        if (status != WrongItemReceiptStatus.AWAITING_OWNER) {
            throw new IllegalStateException("wrong item receipt " + id + " is " + status);
        }
        status = WrongItemReceiptStatus.CONFIRMED;
        confirm(quantity, by, now);
    }

    /** PM 23/11: only the Owner's "receiver miscounted" on the line cancels a phiếu nhận hàng giao sai. */
    public void cancelWrongItemReceipt() {
        if (status != WrongItemReceiptStatus.AWAITING_OWNER) {
            throw new IllegalStateException("wrong item receipt " + id + " is " + status);
        }
        status = WrongItemReceiptStatus.CANCELLED;
    }

    /**
     * PM 23/11: the destination's BM decided the short part will come in a later delivery. Pending, with no GRN, until
     * the receipt that brings it is confirmed ({@link #settle}).
     */
    public static DiscrepancyResolutionEvent lateDeliveryDecision(long caseId, int quantity, String reason, long by,
                                                                  LocalDateTime occurredAt) {
        return pending(caseId, ResolutionType.LATE_DELIVERY_RECEIVED, quantity, null, reason, by, occurredAt);
    }

    /**
     * PM 23/11: the destination's BM does not need the short part any more. Pending until the order closes, which
     * moves it out of transit back to the source and confirms the event.
     */
    public static DiscrepancyResolutionEvent noReplacementDecision(long caseId, int quantity, String reason, long by,
                                                                   LocalDateTime occurredAt) {
        return pending(caseId, ResolutionType.NO_REPLACEMENT, quantity, null, reason, by, occurredAt);
    }

    /** Confirms a pending NO_REPLACEMENT decision when the order closes. */
    public void confirmNoReplacement(long by, LocalDateTime now) {
        confirm(quantity, by, now);
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
