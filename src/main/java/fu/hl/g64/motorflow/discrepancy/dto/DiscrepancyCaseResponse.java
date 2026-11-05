package fu.hl.g64.motorflow.discrepancy.dto;

import fu.hl.g64.motorflow.discrepancy.entity.enums.ClosureType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A supplier discrepancy case (UC-46 step 2). No price is shown (BR-72).
 * <ul>
 *     <li>What the case holds: {@code shortQty}, {@code wrongItemQty}, {@code damagedQty} and
 *     {@code rejectedExcessQty}; the last three are goods kept at the store.</li>
 *     <li>What it requires: {@code returnRequiredQty} to hand back to the supplier, {@code replacementRequiredQty}
 *     to receive again (short + returned - rejected excess; 0 for a case opened on a replacement delivery).</li>
 *     <li>What was done: returned, written off, replaced (accepted when a replacement GRN was confirmed), waived by
 *     agreement, and pending (on a replacement GRN not confirmed yet).</li>
 *     <li>What is left: {@code outstandingReturnQty}, {@code outstandingReplacementQty}; {@code openReplacementQty}
 *     is the part no replacement delivery is bringing yet, which a new replacement GRN or a waiver may take;
 *     {@code heldUndecidedQty} is held goods that are neither to be returned nor written off.</li>
 * </ul>
 * The {@code can...} flags tell what the signed-in user may do now; {@code events} is filled on the detail only.
 */
public record DiscrepancyCaseResponse(
        long id, long goodsReceiptNoteId, boolean replacementDelivery, long purchaseOrderId,
        long supplierId, String supplierName, long storeId, String storeName,
        long productId, String sku, String partName, String baseUnit,
        int shortQty, int wrongItemQty, int damagedQty, int rejectedExcessQty,
        int returnRequiredQty, int replacementRequiredQty,
        int returnedQty, int writtenOffQty, int replacedQty, int waivedQty, int pendingReplacementQty,
        int outstandingReturnQty, int outstandingReplacementQty, int openReplacementQty, int heldUndecidedQty,
        boolean excessAwaitingDecision,
        DiscrepancyCaseStatus status, LocalDateTime openedAt, ClosureType closureType, String closureReason,
        Long closedById, String closedByName, LocalDateTime closedAt,
        boolean canHandle, boolean canRecordReturn, boolean canWaiveReplacement, boolean canCreateReplacement,
        List<Event> events) {

    /**
     * One action on the case, oldest first. A replacement is {@code confirmed} once its GRN is confirmed; its
     * {@code quantity} is then what that confirmation accepted.
     */
    public record Event(long id, ResolutionType type, int quantity, Long goodsReceiptNoteId, String reason,
                        Long recordedById, String recordedByName, LocalDateTime occurredAt, boolean confirmed,
                        LocalDateTime confirmedAt, List<AttachmentResponse> evidence) {
    }
}
