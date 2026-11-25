package fu.hl.g64.motorflow.discrepancy.entity;

import fu.hl.g64.motorflow.discrepancy.entity.enums.ClosureType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DecisionStatus;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DecisionType;
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

    /** The whole return: rejected excess + {@link #wrongReturnRequiredQty} + {@link #damagedReturnRequiredQty}. */
    @Column(name = "return_required_qty", nullable = false)
    private int returnRequiredQty;

    /** Góp ý UI 21/11 (V21): the wrong items agreed to go back to the supplier. */
    @Column(name = "wrong_return_required_qty", nullable = false)
    private int wrongReturnRequiredQty;

    /** Góp ý UI 21/11 (V21): the damaged goods agreed to go back to the supplier. */
    @Column(name = "damaged_return_required_qty", nullable = false)
    private int damagedReturnRequiredQty;

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

    /** GVHD 24/11 (V26): the direction agreed with the supplier, decided or proposed; null while undecided. */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", length = 25)
    private DecisionType decisionType;

    /** Review 25/11: the direction decided before a proposal, in force again if the Owner rejects the proposal. */
    @Enumerated(EnumType.STRING)
    @Column(name = "prior_decision_type", length = 25)
    private DecisionType priorDecisionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_status", nullable = false, length = 15)
    private DecisionStatus decisionStatus = DecisionStatus.UNDECIDED;

    /** GVHD 24/11 (V26): a Branch Manager's proposal awaiting the Owner; null otherwise. */
    @Column(name = "proposed_wrong_return_qty")
    private Integer proposedWrongReturnQty;

    @Column(name = "proposed_damaged_return_qty")
    private Integer proposedDamagedReturnQty;

    @Column(name = "proposed_replacement_qty")
    private Integer proposedReplacementQty;

    /** Who last saved the decision or the proposal, and when. */
    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** The Owner who approved a Branch Manager's proposal, and when. */
    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

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

    public void recordTransferCondition(int wrong, int damaged) {
        wrongItemQty += wrong;
        damagedQty += damaged;
        if (wrong > 0 || damaged > 0) {
            status = DiscrepancyCaseStatus.OPEN;
            closedAt = null;
            closedBy = null;
            closureReason = null;
            closureType = null;
        }
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
    public int defaultReplacement(int wrongReturn, int damagedReturn) {
        int added = Math.max(wrongReturn - wrongReturnRequiredQty, 0)
                + Math.max(damagedReturn - damagedReturnRequiredQty, 0);
        return Math.min(replacementRequiredQty + added, replacementLimit());
    }

    /**
     * UC-46 step 6, BR-27: the wrong items and the damaged goods to return (góp ý UI 21/11: kept apart, the rejected
     * excess always goes back) and the replacement agreed with the supplier. The return may come down again (test
     * report 20/11); the service keeps each kind above what went back already.
     */
    public void agree(int wrongReturn, int damagedReturn, int replacementRequired) {
        requireOpen();
        if (wrongReturn < 0 || wrongReturn > wrongItemQty || damagedReturn < 0 || damagedReturn > damagedQty) {
            throw new IllegalArgumentException("return " + wrongReturn + " wrong / " + damagedReturn
                    + " damaged is outside 0.." + wrongItemQty + " / 0.." + damagedQty);
        }
        // Only Owner settles a shortage without replacement, by a waiver that leaves the requirement as it is.
        if (replacementRequired < shortQty || replacementRequired > replacementLimit()) {
            throw new IllegalArgumentException("replacement required " + replacementRequired + " is outside "
                    + shortQty + ".." + replacementLimit());
        }
        wrongReturnRequiredQty = wrongReturn;
        damagedReturnRequiredQty = damagedReturn;
        returnRequiredQty = rejectedExcessQty + wrongReturn + damagedReturn;
        replacementRequiredQty = replacementRequired;
    }

    /** GVHD 24/11: the direction is decided (by the Owner, or by a Branch Manager within their rights). */
    public void decide(DecisionType type, long by, LocalDateTime now) {
        decisionType = type;
        decisionStatus = DecisionStatus.DECIDED;
        decidedBy = by;
        decidedAt = now;
        clearProposal();
    }

    /** GVHD 24/11: a Branch Manager proposes a direction only the Owner may decide. */
    public void propose(DecisionType type, int wrongReturn, int damagedReturn, int replacement, long by,
                        LocalDateTime now) {
        requireOpen();
        if (decisionStatus == DecisionStatus.DECIDED) {
            priorDecisionType = decisionType;
        }
        decisionType = type;
        decisionStatus = DecisionStatus.AWAITING_OWNER;
        proposedWrongReturnQty = wrongReturn;
        proposedDamagedReturnQty = damagedReturn;
        proposedReplacementQty = replacement;
        decidedBy = by;
        decidedAt = now;
        approvedBy = null;
        approvedAt = null;
    }

    /** GVHD 24/11: the Owner approves the proposal; the caller carries it out. */
    public void approve(long by, LocalDateTime now) {
        if (decisionStatus != DecisionStatus.AWAITING_OWNER) {
            throw new IllegalStateException("discrepancy case " + id + " has no proposal");
        }
        decisionStatus = DecisionStatus.DECIDED;
        approvedBy = by;
        approvedAt = now;
        clearProposal();
    }

    /**
     * GVHD 24/11: the Owner sends the proposal back. What was decided before stays (review 25/11); a case with no
     * earlier decision is undecided again.
     */
    public void rejectProposal() {
        if (decisionStatus != DecisionStatus.AWAITING_OWNER) {
            throw new IllegalStateException("discrepancy case " + id + " has no proposal");
        }
        decisionStatus = priorDecisionType != null ? DecisionStatus.DECIDED : DecisionStatus.UNDECIDED;
        decisionType = priorDecisionType;
        clearProposal();
    }

    public boolean isDecided() {
        return decisionStatus == DecisionStatus.DECIDED;
    }

    private void clearProposal() {
        priorDecisionType = null;
        proposedWrongReturnQty = null;
        proposedDamagedReturnQty = null;
        proposedReplacementQty = null;
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
