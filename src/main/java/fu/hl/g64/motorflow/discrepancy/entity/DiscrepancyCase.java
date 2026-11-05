package fu.hl.g64.motorflow.discrepancy.entity;

import fu.hl.g64.motorflow.discrepancy.entity.enums.ClosureType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType;
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

/** Short, wrong, damaged or rejected-excess goods of one part on one supplier receipt, or a transfer shortage, tracked until resolved. */
@Entity
@Table(name = "discrepancy_case")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscrepancyCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private SourceType sourceType;

    @Column(name = "goods_receipt_note_id")
    private Long goodsReceiptNoteId;

    @Column(name = "transfer_order_id")
    private Long transferOrderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "short_qty", nullable = false)
    private int shortQty;

    @Column(name = "wrong_item_qty", nullable = false)
    private int wrongItemQty;

    @Column(name = "damaged_qty", nullable = false)
    private int damagedQty;

    @Column(name = "rejected_excess_qty", nullable = false)
    private int rejectedExcessQty;

    @Column(name = "return_required_qty", nullable = false)
    private int returnRequiredQty;

    @Column(name = "replacement_required_qty", nullable = false)
    private int replacementRequiredQty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private DiscrepancyCaseStatus status;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_type", length = 25)
    private ClosureType closureType;

    @Column(name = "closure_reason")
    private String closureReason;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /**
     * UC-44 step 7: short, wrong-item or damaged goods of one part on a confirmed supplier receipt. The supplier owes
     * the short quantity from the start (replacement required = short); what is returned is agreed in UC-46. A
     * replacement delivery opens its case with no short quantity: what it did not bring stays owed on the original
     * case. Excess Owner rejects later opens a case with all three at 0 ({@link #addRejectedExcess}).
     */
    public static DiscrepancyCase supplier(long goodsReceiptNoteId, long productId, int shortQty, int wrongItemQty,
                                           int damagedQty, LocalDateTime now) {
        DiscrepancyCase found = new DiscrepancyCase();
        found.sourceType = SourceType.SUPPLIER;
        found.goodsReceiptNoteId = goodsReceiptNoteId;
        found.productId = productId;
        found.shortQty = shortQty;
        found.wrongItemQty = wrongItemQty;
        found.damagedQty = damagedQty;
        found.replacementRequiredQty = shortQty;
        found.status = DiscrepancyCaseStatus.OPEN;
        found.openedAt = now;
        return found;
    }

    public boolean isOpen() {
        return status == DiscrepancyCaseStatus.OPEN;
    }

    /** Goods of the case that are physically held at the store: wrong item, damaged and rejected excess. */
    public int heldQty() {
        return wrongItemQty + damagedQty + rejectedExcessQty;
    }

    /**
     * UC-45 step 6: excess Owner rejected goes back to the supplier, so it is also to be returned (BR-67). It is not
     * to be replaced: it was never on the PO.
     */
    public void addRejectedExcess(int quantity) {
        requireOpen();
        rejectedExcessQty += quantity;
        returnRequiredQty += quantity;
    }

    /**
     * UC-46 step 6: the quantity to return to the supplier, rejected excess included. Everything short, and every
     * wrong or damaged unit sent back, is to be replaced (BR-27) until Owner agrees otherwise; rejected excess is not.
     *
     * @param replaced false for a case opened on a replacement delivery: what that delivery failed to bring is still
     *                 owed on the original case (only accepted replacements settle it, UC-46), so this case only
     *                 tracks the goods to send back and owes no replacement of its own
     */
    public void agreeReturn(int returnRequired, boolean replaced) {
        requireOpen();
        if (returnRequired < returnRequiredQty || returnRequired > heldQty()) {
            throw new IllegalArgumentException("return required " + returnRequired + " is outside "
                    + returnRequiredQty + ".." + heldQty());
        }
        returnRequiredQty = returnRequired;
        replacementRequiredQty = replaced ? shortQty + returnRequired - rejectedExcessQty : 0;
    }

    /** UC-46 step 7: every obligation is settled, or the rest was settled by agreement with the supplier. */
    public void close(ClosureType type, String reason, long closedBy, LocalDateTime now) {
        requireOpen();
        status = DiscrepancyCaseStatus.CLOSED;
        closureType = type;
        closureReason = reason;
        this.closedBy = closedBy;
        closedAt = now;
    }

    private void requireOpen() {
        if (status != DiscrepancyCaseStatus.OPEN) {
            throw new IllegalStateException("discrepancy case " + id + " is " + status);
        }
    }
}
