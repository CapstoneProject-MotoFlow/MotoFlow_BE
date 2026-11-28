package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;

import java.util.Set;

/** Where stock came from or went to in the Inventory Operations Report (UC-88), grouping movement types. */
public enum StockSource {
    /**
     * Goods received from suppliers, including accepted or rejected excess, and discrepancy goods taken as another
     * part (GVHD 20/11).
     */
    PURCHASE_RECEIPT(Set.of(MovementType.RECEIPT_CONFIRMED, MovementType.EXCESS_ACCEPTED,
            MovementType.EXCESS_REJECTED, MovementType.DAMAGED_TO_VARIANT, MovementType.WRONG_ITEM_RECLASSIFIED)),
    /** Goods held for or returned to a supplier. */
    SUPPLIER_RETURN(Set.of(MovementType.RETURN_HOLD, MovementType.RETURN_RELEASE, MovementType.SUPPLIER_RETURN)),
    SALE(Set.of(MovementType.SALE_ISSUE)),
    /** Dispatched out of this warehouse, or sent to it (in transit from dispatch, then received). */
    TRANSFER(Set.of(MovementType.TRANSFER_DISPATCH, MovementType.TRANSFER_EXTRA_DISPATCH,
            MovementType.TRANSFER_RECEIPT, MovementType.TRANSFER_WRONG_ITEM,
            MovementType.TRANSFER_SHORT_RETURNED, MovementType.TRANSFER_RECEIPT_CORRECTED)),
    /** Approved adjustment requests: write-offs and corrections. */
    ADJUSTMENT(Set.of(MovementType.ADJUSTMENT)),
    /** Surplus and shortage found by a stock count. */
    STOCK_COUNT(Set.of(MovementType.COUNT_ADJUSTMENT)),
    OPENING_BALANCE(Set.of(MovementType.OPENING_BALANCE));

    private final Set<MovementType> types;

    StockSource(Set<MovementType> types) {
        this.types = types;
    }

    public static StockSource of(MovementType type) {
        for (StockSource source : values()) {
            if (source.types.contains(type)) {
                return source;
            }
        }
        throw new IllegalArgumentException("no source for " + type);
    }
}
