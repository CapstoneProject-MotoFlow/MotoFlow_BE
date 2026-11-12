package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-106 for the person receiving a PO's goods (screen 6.2.1): what the delivery should bring, never a price or a
 * value (BR-72). {@code code} is "#" + id (BR-01). {@code canCreateReceipt}: a delivery of the outstanding lines can
 * be recorded; {@code canCreateReplacement}: a replacement delivery for {@code openCases} can be recorded.
 */
public record PurchaseOrderReceivingView(long id, String code, PurchaseOrderStatus status, long supplierId,
                                         String supplierCode, String supplierName, long storeId, String storeName,
                                         LocalDate expectedArrival, boolean canCreateReceipt,
                                         boolean canCreateReplacement, List<Line> lines, List<OpenCase> openCases) {

    /** {@code outstandingQty}: ordered and not received yet. */
    public record Line(long productId, String sku, String partName, String baseUnit, int orderedQty,
                       int outstandingQty) {
    }

    /** A discrepancy case a replacement delivery may still bring {@code openReplacementQty} for. */
    public record OpenCase(long caseId, long goodsReceiptNoteId, long productId, String sku, String partName,
                           int openReplacementQty) {
    }
}
