package fu.hl.g64.motorflow.inventory.dto;

/**
 * JOB-02: one part of one warehouse whose sellable stock (available, not expired) is below its low-stock threshold
 * (UC-51).
 */
public record LowStockRow(long storeId, long productId, String sku, String partName, int availableQty,
                          int sellableQty, int lowStockThreshold) {
}
