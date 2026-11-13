package fu.hl.g64.motorflow.sales.entity.enums;

/** Values of sales_order.status (CHECK in the schema). */
public enum SalesOrderStatus {
    /** Saved by its seller on the server; not a sales order until submitted (PM 13/11). */
    DRAFT,
    AWAITING_PICKING,
    PICKED,
    CONFIRMED,
    COMPLETED,
    CANCELLED
}
