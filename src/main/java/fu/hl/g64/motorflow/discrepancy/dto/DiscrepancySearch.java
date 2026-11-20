package fu.hl.g64.motorflow.discrepancy.dto;

import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;

import java.time.LocalDate;

/**
 * Filters of the supplier discrepancy list, all optional. GVHD 20/11 (PR 6): {@code supplierId} of the PO, the day
 * the delivery arrived from {@code deliveredFrom} to {@code deliveredTo} inclusive, and {@code q}: part of the SKU or
 * part name, without case or accents.
 */
public record DiscrepancySearch(DiscrepancyCaseStatus status, Long storeId, Long purchaseOrderId,
                                Long goodsReceiptNoteId, Long supplierId, LocalDate deliveredFrom,
                                LocalDate deliveredTo, String q) {

    public DiscrepancySearch(DiscrepancyCaseStatus status, Long storeId, Long purchaseOrderId,
                             Long goodsReceiptNoteId) {
        this(status, storeId, purchaseOrderId, goodsReceiptNoteId, null, null, null, null);
    }
}
