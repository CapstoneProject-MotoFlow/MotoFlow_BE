package fu.hl.g64.motorflow.document.dto;

/**
 * The printable forms of UC-80. A completed sales order prints as a retail invoice or a stock issue note, a dispatched
 * transfer order as a stock issue note, a confirmed goods receipt as a goods receipt note, a verified payment as a
 * receipt (or refund slip) and an adjustment request as an adjustment note.
 */
public enum PrintForm {
    SALES_INVOICE,
    STOCK_ISSUE_NOTE,
    GOODS_RECEIPT_NOTE,
    PAYMENT_RECEIPT,
    ADJUSTMENT_NOTE
}
