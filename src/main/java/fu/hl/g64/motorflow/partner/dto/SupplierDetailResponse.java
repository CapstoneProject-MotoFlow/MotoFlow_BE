package fu.hl.g64.motorflow.partner.dto;

import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-32: the supplier with its newest purchase orders, deliveries and discrepancies.
 * Each list holds at most the newest 20 rows; the counts cover all of them.
 */
public record SupplierDetailResponse(SupplierResponse supplier,
                                     List<PurchaseOrder> purchaseOrders, long purchaseOrderCount,
                                     List<Delivery> deliveries, long deliveryCount,
                                     List<Discrepancy> discrepancies, long discrepancyCount, long openDiscrepancyCount) {

    /** {@code orderedValue} is the sum of ordered quantity × agreed purchase price, in VND. */
    public record PurchaseOrder(long id, long storeId, String storeName, PurchaseOrderStatus status,
                                LocalDate expectedArrival, long lineCount, long orderedValue, LocalDateTime createdAt) {
    }

    public record Delivery(long id, long purchaseOrderId, String deliveryDocumentNo, GoodsReceiptNoteStatus status,
                           LocalDateTime arrivedAt, LocalDateTime confirmedAt) {
    }

    public record Discrepancy(long id, long deliveryId, long productId, String sku, String partName, int shortQty,
                              int wrongItemQty, int damagedQty, int rejectedExcessQty, DiscrepancyCaseStatus status,
                              LocalDateTime openedAt, LocalDateTime closedAt) {
    }
}
