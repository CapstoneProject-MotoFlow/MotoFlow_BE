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
    OPENING_BALANCE,
    /** GVHD 20/11 (PR 5, V15): damaged goods leave the part and come in as its dented-goods variant. */
    DAMAGED_TO_VARIANT,
    /** GVHD 20/11 (PR 5, V15): wrong items leave the part ordered and come in as the part they really are. */
    WRONG_ITEM_RECLASSIFIED,
    /** PM 23/11 (V23): an Owner-confirmed transfer wrong item leaves the source for the destination. */
    TRANSFER_WRONG_ITEM,
    /** PM 23/11 (V23): on closing, a short part not to be replaced leaves transit back to the source. */
    TRANSFER_SHORT_RETURNED,
    /** PM 23/11 (V23): the receiver miscounted; the destination gets what was dispatched. */
    TRANSFER_RECEIPT_CORRECTED
}
