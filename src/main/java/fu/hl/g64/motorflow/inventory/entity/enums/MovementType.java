package fu.hl.g64.motorflow.inventory.entity.enums;

/** Values of stock_movement.movement_type (CHECK in the schema). */
public enum MovementType {
    SALE_ISSUE,
    RECEIPT_CONFIRMED,
    EXCESS_ACCEPTED,
    EXCESS_REJECTED,
    RETURN_HOLD,
    /** Goods taken back from awaiting return to supplier to the status they were held from (V14). */
    RETURN_RELEASE,
    SUPPLIER_RETURN,
    TRANSFER_DISPATCH,
    TRANSFER_EXTRA_DISPATCH,
    TRANSFER_RECEIPT,
    ADJUSTMENT,
    COUNT_ADJUSTMENT,
    OPENING_BALANCE
}
