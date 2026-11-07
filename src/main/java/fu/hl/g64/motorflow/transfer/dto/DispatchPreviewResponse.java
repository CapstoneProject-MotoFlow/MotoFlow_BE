package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;

import java.util.List;

/**
 * UC-74 before confirming: what was prepared and the lots the dispatch would take, in the order the posting takes them
 * (FEFO, then FIFO, expired lots skipped). Nothing is reserved, so the lots can change until the dispatch is confirmed.
 *
 * @param readyToConfirm the preparer has sent their result and the order still waits for dispatch
 * @param canConfirm     ready, and the user is a source Branch Manager other than the preparer (BR-26)
 */
public record DispatchPreviewResponse(long id, TransferOrderStatus status, Long preparedBy, boolean readyToConfirm,
                                      boolean canConfirm, List<Line> lines) {

    /** {@code lots} cover the prepared quantity, or the planned one before anything is prepared. */
    public record Line(long productId, String sku, String partName, String baseUnit, int plannedQty,
                       Integer preparedQty, Integer shortageQty, int sellableQty, List<PickingGuide.LotToPick> lots) {
    }
}
