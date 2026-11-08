package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;

import java.time.LocalDateTime;
import java.util.List;

/** UC-70: one replenishment request, with the transfer orders and POs created for it. */
public record ReplenishmentDetailResponse(long id, long requestingStoreId, String storeName,
                                          ReplenishmentRequestStatus status, String note, long createdBy,
                                          String createdByName, LocalDateTime createdAt, LocalDateTime closedAt,
                                          List<Line> lines, List<TransferOrderRef> transferOrders,
                                          List<PurchaseOrderRef> purchaseOrders) {

    /**
     * @param plannedQty   on the transfer orders of this request, the dispatched quantity once dispatched
     * @param fulfilledQty received and passed at the destination, on confirmed transfer receipts
     * @param remainingQty requested less fulfilled and to purchase, never below 0
     */
    public record Line(long productId, String sku, String partName, String baseUnit, int requestedQty, long plannedQty,
                       long fulfilledQty, int toPurchaseQty, long remainingQty) {
    }

    public record TransferOrderRef(long id, long sourceStoreId, String sourceStoreName, long destStoreId,
                                   TransferOrderStatus status, LocalDateTime createdAt) {
    }

    public record PurchaseOrderRef(long id, long supplierId, String supplierName, PurchaseOrderStatus status,
                                   LocalDateTime createdAt) {
    }
}
