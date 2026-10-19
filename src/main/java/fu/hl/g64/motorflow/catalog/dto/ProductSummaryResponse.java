package fu.hl.g64.motorflow.catalog.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;

import java.util.List;

/**
 * A row of SCR-07 / SCR-08. Stock fields describe the store being viewed and are null when no store is
 * viewed (Owner without {@code storeId}); {@code retailPrice} is null while no price is in effect.
 */
public record ProductSummaryResponse(long id, String sku, String partName, String brand, String baseUnit,
                                     long categoryId, String categoryName, List<String> oemCodes,
                                     ProductStatus status, Long retailPrice, Integer availableQty,
                                     String storageArea, String binLocation) {
}
