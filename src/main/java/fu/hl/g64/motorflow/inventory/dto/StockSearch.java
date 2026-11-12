package fu.hl.g64.motorflow.inventory.dto;

/**
 * UC-47 filters. {@code storeId} null: the Branch Manager's own warehouse, or every warehouse for the Owner.
 *
 * @param q               SKU, part name, OEM code or bin, contains, case-insensitive
 * @param storageArea     exact area, case-insensitive
 * @param lowStockOnly    only parts whose available stock is below the threshold
 * @param unavailableOnly only parts with stock awaiting inspection, wrong, damaged or awaiting supplier return
 * @param expiryAlertOnly only parts with available stock in lots expired or in the warning window (BR-76)
 * @param includeZero     keep rows without available stock, which the list hides unless one of the "only" filters is on
 * @param productId       one part only
 * @param minQty          only rows whose available quantity is at least this, inclusive; null: no lower bound
 * @param maxQty          only rows whose available quantity is at most this, inclusive; null: no upper bound
 */
public record StockSearch(Long storeId, String q, Long categoryId, String storageArea, boolean lowStockOnly,
                          boolean unavailableOnly, boolean expiryAlertOnly, boolean includeZero, Long productId,
                          Integer minQty, Integer maxQty) {

    /** Scope only, for what is counted over the whole filter. */
    public static StockSearch scope(Long storeId, String q, Long categoryId, String storageArea) {
        return new StockSearch(storeId, q, categoryId, storageArea, false, false, false, false, null, null, null);
    }

    /**
     * A row with no available stock still matters when the user asked for low, unavailable or expiring stock, or for a
     * stock range starting at 0.
     */
    public boolean hidesZeroStock() {
        return !includeZero && !lowStockOnly && !unavailableOnly && !expiryAlertOnly
                && (minQty == null || minQty > 0);
    }
}
