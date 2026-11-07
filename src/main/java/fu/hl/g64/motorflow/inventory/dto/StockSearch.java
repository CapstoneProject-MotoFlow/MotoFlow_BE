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
 */
public record StockSearch(Long storeId, String q, Long categoryId, String storageArea, boolean lowStockOnly,
                          boolean unavailableOnly, boolean expiryAlertOnly, boolean includeZero) {

    /** Scope only, for what is counted over the whole filter. */
    public static StockSearch scope(Long storeId, String q, Long categoryId, String storageArea) {
        return new StockSearch(storeId, q, categoryId, storageArea, false, false, false, false);
    }

    /** A row with no available stock still matters when the user asked for low, unavailable or expiring stock. */
    public boolean hidesZeroStock() {
        return !includeZero && !lowStockOnly && !unavailableOnly && !expiryAlertOnly;
    }
}
