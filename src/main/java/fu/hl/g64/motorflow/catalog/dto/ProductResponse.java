package fu.hl.g64.motorflow.catalog.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;

import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-14. {@code stock} belongs to the store being viewed and is null when no store is viewed; {@code retailPrice}
 * is null while none is in effect. There is no per-store wholesale price (BR-10).
 */
public record ProductResponse(long id, String sku, String partName, long categoryId, String categoryName,
                              String brand, String countryOfOrigin, String baseUnit, String specifications,
                              ProductStatus status, LocalDateTime discontinuedAt, List<String> oemCodes,
                              List<String> barcodes, List<String> imageUrls, List<VehicleModelResponse> vehicleModels,
                              Long retailPrice, Stock stock,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {

    /** Quantities by stock status (BR-29) and where the part is kept in the viewed store. */
    public record Stock(long storeId, int availableQty, int awaitingInspectionQty, int wrongItemQty, int damagedQty,
                        int awaitingSupplierReturnQty, int inTransitQty, String storageArea, String binLocation,
                        Integer lowStockThreshold) {

        /** {@code balance} is null when the store has never had the part. */
        public static Stock of(long storeId, StockBalance balance) {
            return balance == null ? new Stock(storeId, 0, 0, 0, 0, 0, 0, null, null, null)
                    : new Stock(storeId, balance.getAvailableQty(), balance.getAwaitingInspectionQty(),
                    balance.getWrongItemQty(), balance.getDamagedQty(), balance.getAwaitingSupplierReturnQty(),
                    balance.getInTransitQty(), balance.getStorageArea(), balance.getBinLocation(),
                    balance.getLowStockThreshold());
        }
    }
}
