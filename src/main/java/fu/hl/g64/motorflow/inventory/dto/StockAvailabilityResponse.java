package fu.hl.g64.motorflow.inventory.dto;

/** UC-48: sellable stock of one part in one active warehouse (available, not expired). */
public record StockAvailabilityResponse(long productId, String sku, long storeId, String storeName, boolean central,
                                        int sellableQty) {
}
