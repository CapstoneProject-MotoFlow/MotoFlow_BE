package fu.hl.g64.motorflow.sales.entity.enums;

/** Values of sales_order.status (CHECK in the schema). */
public enum SalesOrderStatus {
    /** Saved by its seller on the server; not a sales order until submitted (PM 13/11). */
    DRAFT,
    /**
     * GVHD 20/11: what the store could not sell now, waiting for stock. It holds no goods, is not picked and takes no
     * money until it is released to picking (in part or in full).
     */
    AWAITING_STOCK,
    AWAITING_PICKING,
    PICKED,
    CONFIRMED,
    COMPLETED,
    CANCELLED
}
