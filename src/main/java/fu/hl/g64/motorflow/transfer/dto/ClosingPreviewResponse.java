package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;

import java.util.List;

/**
 * UC-77 before closing: the final figures of each part and what still stops the order from closing.
 *
 * @param blockedBy the message codes closing would fail with, in the order they are checked: MSG221 parts without a
 *                  final result (BR-63), MSG222 excess the source has not confirmed, MSG223 source stock short of the
 *                  confirmed excess
 */
public record ClosingPreviewResponse(long id, TransferOrderStatus status, boolean canClose, List<String> blockedBy,
                                     List<Line> lines) {

    /** {@code extraDispatchedQty} moves from the source's available stock to the destination's on closing (BR-68). */
    public record Line(long productId, String sku, String partName, String baseUnit, int dispatchedQty,
                       int receivedQty, int returnedQty, int lossQty, int unresolvedQty, int extraReceivedQty,
                       int extraDispatchedQty, int sourceSellableQty) {
    }
}
