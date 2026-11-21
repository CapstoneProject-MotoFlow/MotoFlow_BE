package fu.hl.g64.motorflow.receivable.entity.enums;

/** Values of payment.payment_type (CHECK in the schema). */
public enum PaymentType {
    ORDER_PAYMENT,
    REFUND,
    DEBT_PAYMENT,
    /** PM 21/11: money a customer leaves on a waiting order (AWAITING_STOCK) before the goods are there. */
    DEPOSIT
}
