package fu.hl.g64.motorflow.catalog.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;

/**
 * BR-77: the one part a scanned barcode belongs to. {@code storeId} and {@code availableQty} are null when no store
 * is viewed (Owner without {@code storeId}). No prices: the sales screen reads them from its own API.
 */
public record BarcodeLookupResponse(long productId, String sku, String partName, String brand, String baseUnit,
                                    ProductStatus status, String barcode, Long storeId, Integer availableQty) {
}
