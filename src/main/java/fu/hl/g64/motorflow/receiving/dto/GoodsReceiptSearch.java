package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;

/**
 * Filters of the GRN list, all optional. {@code excessAwaiting}: only receipts with excess Owner has not decided on;
 * {@code unassigned}: only receipts waiting for inspection that nobody holds (Home "receipts to assign").
 */
public record GoodsReceiptSearch(Long storeId, Long purchaseOrderId, GoodsReceiptNoteStatus status,
                                 Boolean excessAwaiting, Boolean unassigned) {
}
