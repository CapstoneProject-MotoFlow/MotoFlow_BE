package fu.hl.g64.motorflow.inventory.dto;

/** One part's closing quantity at the end of a past date (UC-103). */
public record PastDateStockRow(long productId, String sku, String partName, String baseUnit, int closingQty) {
}
