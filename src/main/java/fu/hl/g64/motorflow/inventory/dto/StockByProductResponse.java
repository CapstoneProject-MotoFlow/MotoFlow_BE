package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;

import java.math.BigDecimal;

/**
 * UC-47, Owner: one part with its stock added up over every warehouse in scope.
 *
 * @param storeCount         warehouses holding the part, goods in transit included
 * @param onHandQty          everything held in any status; goods in transit are not included
 * @param nearExpiryQty     available quantity in lots that expire within the warning days (BR-76)
 * @param expiredQty         available quantity in expired lots
 * @param lowStockStoreCount warehouses where available stock is below the threshold
 * @param movingAvgCost      null while the part has never been received
 * @param stockValue         moving average cost times the valued stock (as in {@link StockRowResponse}); 0 without
 *                           such stock, null when there is stock but no cost
 */
public record StockByProductResponse(
        long productId,
        String sku,
        String partName,
        String brand,
        String baseUnit,
        ProductStatus productStatus,
        int storeCount,
        int availableQty,
        int awaitingInspectionQty,
        int wrongItemQty,
        int damagedQty,
        int awaitingSupplierReturnQty,
        int inTransitQty,
        int onHandQty,
        int nearExpiryQty,
        int expiredQty,
        int lowStockStoreCount,
        BigDecimal movingAvgCost,
        Long stockValue) {
}
