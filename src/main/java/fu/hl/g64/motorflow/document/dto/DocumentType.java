package fu.hl.g64.motorflow.document.dto;

/**
 * The posted documents UC-78 searches (screen 26). Only documents that took effect are listed: a sales order once
 * completed or cancelled, a goods receipt once confirmed, a transfer order once dispatched, a payment once verified
 * and an adjustment request once submitted.
 */
public enum DocumentType {
    SALES_ORDER,
    GOODS_RECEIPT,
    TRANSFER_ORDER,
    PAYMENT,
    ADJUSTMENT
}
