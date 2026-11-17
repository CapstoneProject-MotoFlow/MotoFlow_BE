package fu.hl.g64.motorflow.receiving.entity.enums;

/** Values of purchase_order.status (CHECK in the schema). */
public enum PurchaseOrderStatus {
    /** PM 17/11: saved by its creator, not a PO until submitted; only the drafts API sees it. */
    DRAFT,
    AWAITING_DELIVERY,
    RECEIVING,
    FULLY_RECEIVED,
    CANCELLED
}
