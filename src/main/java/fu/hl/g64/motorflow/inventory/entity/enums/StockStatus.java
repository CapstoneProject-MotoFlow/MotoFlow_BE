package fu.hl.g64.motorflow.inventory.entity.enums;

/** Values of stock_movement.stock_status (CHECK in the schema). */
public enum StockStatus {
    AVAILABLE,
    AWAITING_INSPECTION,
    WRONG_ITEM,
    DAMAGED,
    AWAITING_SUPPLIER_RETURN,
    IN_TRANSIT
}
