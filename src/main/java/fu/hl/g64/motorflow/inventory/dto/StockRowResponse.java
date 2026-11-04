package fu.hl.g64.motorflow.inventory.dto;

/**
 * UC-48: one part in one warehouse. {@code stockValue} is filled for the Owner only (BR-72); it values the stock that
 * carries the moving average (available, awaiting inspection, in transit).
 *
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
        String storageArea,
        String binLocation,
        Integer lowStockThreshold,
        int availableQty,
        int awaitingInspectionQty,
        int wrongItemQty,
        int damagedQty,
        int awaitingSupplierReturnQty,
        int inTransitQty,
        int nearExpiryQty,
        int expiredQty,
        boolean lowStock,
        Long stockValue) {
}
