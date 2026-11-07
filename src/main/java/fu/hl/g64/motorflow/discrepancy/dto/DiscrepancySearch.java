package fu.hl.g64.motorflow.discrepancy.dto;

import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;

/** Filters of the supplier discrepancy list, all optional. */
public record DiscrepancySearch(DiscrepancyCaseStatus status, Long storeId, Long purchaseOrderId,
                                Long goodsReceiptNoteId) {
}
