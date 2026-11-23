package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;

import java.util.List;

/**
 * UC-77 before closing: the final figures of each part and what still stops the order from closing.
 *
 * @param blockedBy the message codes closing would fail with, in the order they are checked: MSG336 not acknowledged
 *                  by the destination, MSG342 wrong items without a confirmed phiếu nhận hàng giao sai, MSG340 return
 *                  or loss decisions of orders before PM 23/11 still open, MSG348 late deliveries not arrived yet,
 *                  MSG339 / MSG341 the source cannot give the excess to accept, MSG222 / MSG223 excess of orders
 *                  before Q7, MSG225 receipts not yet confirmed
 */
public record ClosingPreviewResponse(long id, TransferOrderStatus status, boolean canClose, List<String> blockedBy,
                                     List<Line> lines) {

    /**
     * {@code extraDispatchedQty} moves from the source's available stock to the destination's on closing (BR-68).
     * PM 23/11: on closing {@code excessToAcceptQty} (the excess still awaiting the Owner) is accepted,
     * {@code toSourceQty} (not to be replaced) goes back to the source's available stock and {@code toLossQty} (the
     * shortage nobody chose a late delivery or no replacement for, or whose late delivery did not come) is written
     * off as lost. {@code unresolvedQty} is what is still in transit at the destination, {@code shortageQty} of it
     * not to be replaced excluded; {@code awaitingDecisionQty} the shortage without a decision yet.
     * {@code wrongPendingQty}: wrong items without a phiếu nhận hàng giao sai; {@code receiverMiscounted}: the Owner
     * corrected the line, the destination holds what was dispatched. {@code latePendingQty}: chosen to come in a later
     * delivery and not arrived yet; it stops the order from closing (MSG348).
     */
    public record Line(long productId, String sku, String partName, String baseUnit, int dispatchedQty,
                       int receivedQty, int returnedQty, int lossQty, int unresolvedQty, int extraReceivedQty,
                       int extraDispatchedQty, int sourceSellableQty, int damagedQty, int legacyExcessQty,
                       int pendingExcessQty, int shortageQty, int awaitingDecisionQty, int toSourceQty, int toLossQty,
                       int excessToAcceptQty, int wrongItemQty, int wrongPendingQty, int wrongAwaitingOwnerQty,
                       boolean receiverMiscounted, int latePendingQty) {
    }
}
