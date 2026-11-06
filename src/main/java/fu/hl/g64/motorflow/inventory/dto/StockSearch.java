package fu.hl.g64.motorflow.inventory.dto;

/**
 * UC-47 filters. {@code storeId} null: the Branch Manager's own warehouse, or every warehouse for the Owner.
 *
 * @param q            SKU or part name, contains, case-insensitive
 * @param lowStockOnly only parts whose sellable stock is below the threshold
 */
public record StockSearch(Long storeId, String q, Long categoryId, boolean lowStockOnly) {
}
