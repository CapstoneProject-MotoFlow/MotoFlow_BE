package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;

/**
 * UC-47: one part in one warehouse. {@code stockValue} is filled for the Owner only (BR-72); it values the stock that
 * carries the moving average (available, awaiting inspection, in transit).
 *
 * @param onHandQty     everything held in this warehouse in any status; goods in transit are not included
 * @param nearExpiryQty available quantity in lots that expire within the warning days (BR-76)
 * @param expiredQty    available quantity in expired lots, which cannot be sold
 */
public record StockRowResponse(
        long storeId,
        String storeName,
        long productId,
        String sku,
        String partName,
        String baseUnit,
        ProductStatus productStatus,
        String storageArea,
        String binLocation,
        Integer lowStockThreshold,
        int availableQty,
        int awaitingInspectionQty,
        int wrongItemQty,
        int damagedQty,
        int awaitingSupplierReturnQty,
        int inTransitQty,
        int onHandQty,
        int nearExpiryQty,
        int expiredQty,
        boolean lowStock,
        Long stockValue) {
}
