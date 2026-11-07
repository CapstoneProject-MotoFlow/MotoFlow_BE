package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.sales.helper.PickingGuide.LotToPick;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;

import java.util.List;

/**
 * UC-57 step 2 for an outgoing transfer: what to take out of the source warehouse, where it is kept and which lots to
 * take first (BR-75), with what was recorded so far. Confirming the dispatch (UC-74) reads {@code preparedQty}.
 */
public record TransferPreparationResponse(long id, TransferOrderStatus status, long sourceStoreId,
                                          String sourceStoreName, long destStoreId, String destStoreName,
                                          Preparer preparer, boolean canRecord, List<Line> lines) {

    public record Preparer(long id, String name, WarehouseTaskStatus taskStatus) {
    }

    public record Line(long id, long productId, String sku, String partName, String baseUnit, List<String> barcodes,
                       String storageArea, String binLocation, int plannedQty, Integer preparedQty, Integer shortQty,
                       String shortReason, int sellableQty, List<LotToPick> pickFirst) {
    }
}
