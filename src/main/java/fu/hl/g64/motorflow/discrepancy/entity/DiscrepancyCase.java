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

    /** At most what may be agreed to replace (V2); null on a case saved before V2: see {@link #replacementLimit()}. */
    @Column(name = "replacement_limit_qty")
    private Integer replacementLimitQty;

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
     * the short quantity from the start (replacement required = short); return and replacement are agreed in UC-46.
     * {@code replacementLimit} is what the PO still lacked once the receipt was stocked, at most short + wrong +
     * damaged (BR-67: nothing beyond the PO comes in without Owner). A replacement delivery opens its case with no
     * short quantity and a limit of 0: what it did not bring stays owed on the original case. Excess Owner rejects
     * later opens a case with everything at 0 ({@link #addRejectedExcess}).
     */
    public static DiscrepancyCase supplier(long goodsReceiptNoteId, long productId, int shortQty, int wrongItemQty,
                                           int damagedQty, int replacementLimit, LocalDateTime now) {
        if (shortQty > replacementLimit || replacementLimit > shortQty + wrongItemQty + damagedQty) {
            throw new IllegalArgumentException("replacement limit " + replacementLimit + " is outside " + shortQty
                    + ".." + (shortQty + wrongItemQty + damagedQty));
        }
        DiscrepancyCase found = new DiscrepancyCase();
        found.sourceType = SourceType.SUPPLIER;
        found.goodsReceiptNoteId = goodsReceiptNoteId;
        found.productId = productId;
        found.shortQty = shortQty;
        found.wrongItemQty = wrongItemQty;
        found.damagedQty = damagedQty;
        found.replacementLimitQty = replacementLimit;
        found.replacementRequiredQty = shortQty;
        found.status = DiscrepancyCaseStatus.OPEN;
        found.openedAt = now;
        return found;
    }

    /**
     * UC-44 for a transfer: what a confirmed transfer receipt left unresolved for one part (BR-59). It is settled by a
     * late receipt, a return to the source or an applied loss (UC-76), never by a supplier replacement.
     */
    public static DiscrepancyCase transfer(long transferOrderId, long productId, int shortQty, LocalDateTime now) {
        DiscrepancyCase found = new DiscrepancyCase();
        found.sourceType = SourceType.TRANSFER;
        found.transferOrderId = transferOrderId;
        found.productId = productId;
        found.shortQty = shortQty;
        found.replacementLimitQty = 0;
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
     * UC-46, BR-27, BR-67: the most that may be agreed to replace. Short, wrong and damaged goods, never rejected
     * excess, and never more than the PO still lacked after the receipt; 0 on a replacement delivery's own case.
     */
    public int replacementLimit() {
        return replacementLimitQty != null ? replacementLimitQty : shortQty + wrongItemQty + damagedQty;
    }

    /**
     * The replacement when the handler leaves it empty: the one agreed so far, plus every wrong or damaged unit newly
     * agreed to go back (rejected excess is in the return from the start), within {@link #replacementLimit()}. On a
     * case not handled yet that is everything short and every wrong or damaged unit sent back.
     */
    public int defaultReplacement(int returnRequired) {
        return Math.min(replacementRequiredQty + Math.max(returnRequired - returnRequiredQty, 0), replacementLimit());
    }

    /** UC-46 step 6, BR-27: the return (rejected excess included) and the replacement agreed with the supplier, kept apart. */
    public void agree(int returnRequired, int replacementRequired) {
        requireOpen();
        if (returnRequired < returnRequiredQty || returnRequired > heldQty()) {
            throw new IllegalArgumentException("return required " + returnRequired + " is outside "
                    + returnRequiredQty + ".." + heldQty());
        }
        // Only Owner settles a shortage without replacement, by a waiver that leaves the requirement as it is.
        if (replacementRequired < shortQty || replacementRequired > replacementLimit()) {
            throw new IllegalArgumentException("replacement required " + replacementRequired + " is outside "
                    + shortQty + ".." + replacementLimit());
        }
        returnRequiredQty = returnRequired;
        replacementRequiredQty = replacementRequired;
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
