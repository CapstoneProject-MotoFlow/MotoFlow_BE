package fu.hl.g64.motorflow.catalog.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;

/**
 * UC-13 filters, all optional. {@code q} matches SKU, name, OEM code or barcode; {@code categoryId} includes its
 * sub-categories; {@code storeId} picks the store whose stock is shown (default: the user's own).
 */
public record ProductSearch(String q, Long categoryId, String brand, VehicleType vehicleType, Long vehicleModelId,
                            ProductStatus status, Boolean inStockOnly, Long storeId) {
}
