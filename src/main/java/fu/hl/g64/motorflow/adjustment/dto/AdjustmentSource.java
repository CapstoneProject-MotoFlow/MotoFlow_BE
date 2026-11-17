package fu.hl.g64.motorflow.adjustment.dto;

/**
 * The document a request comes from: {@code type} is SALES_ORDER, PAYMENT, GOODS_RECEIPT_NOTE, TRANSFER_ORDER or
 * RECEIVABLE, {@code number} its document number ("#" and the id).
 */
public record AdjustmentSource(String type, long id, String number) {
}
