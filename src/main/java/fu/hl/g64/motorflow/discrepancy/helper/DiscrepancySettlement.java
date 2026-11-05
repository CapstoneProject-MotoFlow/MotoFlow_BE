package fu.hl.g64.motorflow.discrepancy.helper;

import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ClosureType;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How far a supplier discrepancy case is settled (UC-46, BR-27), from its resolution events. The case keeps what is
 * required (return_required_qty, replacement_required_qty); the events record what was done against it:
 * <ul>
 *     <li>returned: goods handed back to the supplier (RETURNED_TO_SUPPLIER);</li>
 *     <li>written off: held goods a loss or damage request wrote off (APPROVED_LOSS; comes with the adjustment
 *     use cases);</li>
 *     <li>replaced: replacements accepted when their GRN was confirmed (REPLACEMENT_RECEIVED, LATE_DELIVERY_RECEIVED);
 *     a replacement GRN not confirmed yet is pending;</li>
 *     <li>waived: replacement Owner agreed with the supplier not to receive (CLOSED_BY_AGREEMENT).</li>
 * </ul>
 * A case closes when nothing is left to return or to replace, no held goods wait for a decision and Owner has decided
 * the excess of the same line.
 */
@Component
@RequiredArgsConstructor
public class DiscrepancySettlement {

    private final DiscrepancyResolutionEventRepository events;
    private final GoodsReceiptNoteLineRepository receiptLines;

    /** What was done on one case; {@code lastWaiverReason} is the reason of the latest waiver, if any. */
    public record Progress(int returned, int writtenOff, int replaced, int waived, int pendingReplacement,
                           String lastWaiverReason) {
        public static final Progress NONE = new Progress(0, 0, 0, 0, 0, null);

        private Progress add(DiscrepancyResolutionEvent event) {
            int q = event.getQuantity();
            return switch (event.getResolutionType()) {
                case RETURNED_TO_SUPPLIER ->
                        new Progress(returned + q, writtenOff, replaced, waived, pendingReplacement, lastWaiverReason);
                case APPROVED_LOSS ->
                        new Progress(returned, writtenOff + q, replaced, waived, pendingReplacement, lastWaiverReason);
                case REPLACEMENT_RECEIVED, LATE_DELIVERY_RECEIVED -> event.isConfirmed()
                        ? new Progress(returned, writtenOff, replaced + q, waived, pendingReplacement, lastWaiverReason)
                        : new Progress(returned, writtenOff, replaced, waived, pendingReplacement + q, lastWaiverReason);
                case CLOSED_BY_AGREEMENT ->
                        new Progress(returned, writtenOff, replaced, waived + q, pendingReplacement, event.getReason());
                // Moving goods to awaiting return changes no obligation; the case records it in return_required_qty.
                case MOVED_TO_AWAITING_RETURN, RECEIVED_BACK_AT_SOURCE -> this;
            };
        }
    }

    /** A case with its progress, and what it still requires. */
    public record Standing(DiscrepancyCase found, Progress progress) {

        /** Agreed to go back to the supplier and not handed back yet. */
        public int outstandingReturn() {
            return Math.max(found.getReturnRequiredQty() - progress.returned(), 0);
        }

        /** Still to be replaced: neither replaced nor waived. */
        public int outstandingReplacement() {
            return Math.max(found.getReplacementRequiredQty() - progress.replaced() - progress.waived(), 0);
        }

        /** Outstanding replacement that no replacement delivery is bringing yet: what a new one or a waiver may take. */
        public int openReplacement() {
            return Math.max(outstandingReplacement() - progress.pendingReplacement(), 0);
        }

        /**
         * Wrong, damaged or rejected goods held at the store that are not to be returned and were not written off:
         * they wait for a loss or damage request (UC-46 Step 3_Goods cannot be returned).
         */
        public int heldUndecided() {
            return Math.max(found.heldQty() - found.getReturnRequiredQty() - progress.writtenOff(), 0);
        }

        /**
         * What the replacement delivery of {@code pending} may still bring for this case (UC-44 Step 1_Supplier
         * replacement GRN): the quantity it was created for, but no more than the case still owes.
         */
        public int expectedFrom(DiscrepancyResolutionEvent pending) {
            return found.isOpen() ? Math.min(pending.getQuantity(), outstandingReplacement()) : 0;
        }
    }

    /** The progress of each case; a case with no event has {@link Progress#NONE}. */
    public Map<Long, Progress> progress(Collection<Long> caseIds) {
        Map<Long, Progress> byCase = new HashMap<>();
        if (caseIds.isEmpty()) {
            return byCase;
        }
        for (DiscrepancyResolutionEvent event : events.findByDiscrepancyCaseIdInOrderByOccurredAtAscIdAsc(caseIds)) {
            Long caseId = event.getDiscrepancyCaseId();
            byCase.put(caseId, byCase.getOrDefault(caseId, Progress.NONE).add(event));
        }
        caseIds.forEach(id -> byCase.putIfAbsent(id, Progress.NONE));
        return byCase;
    }

    public Standing standing(DiscrepancyCase found) {
        return new Standing(found, progress(List.of(found.getId())).get(found.getId()));
    }

    /**
     * Closes the open case if every obligation is settled (UC-46 step 7, BR-27): ALL_SETTLED, or CLOSED_BY_AGREEMENT
     * with the reason of the latest waiver when part of the replacement was waived. The caller holds the case lock.
     *
     * @return whether the case is closed now
     */
    public boolean closeIfSettled(DiscrepancyCase found, long actorId, LocalDateTime now) {
        if (!found.isOpen()) {
            return false;
        }
        Standing standing = standing(found);
        if (standing.outstandingReturn() > 0 || standing.outstandingReplacement() > 0
                || standing.progress().pendingReplacement() > 0 || standing.heldUndecided() > 0) {
            return false;
        }
        // Excess of the same line that Owner has not decided on may still add rejected goods to the case (UC-45).
        if (receiptLines.existsByGoodsReceiptNoteIdAndProductIdAndExcessDecision(found.getGoodsReceiptNoteId(),
                found.getProductId(), ExcessDecision.AWAITING_OWNER)) {
            return false;
        }
        boolean agreed = standing.progress().waived() > 0;
        found.close(agreed ? ClosureType.CLOSED_BY_AGREEMENT : ClosureType.ALL_SETTLED,
                agreed ? standing.progress().lastWaiverReason() : null, actorId, now);
        return true;
    }
}
