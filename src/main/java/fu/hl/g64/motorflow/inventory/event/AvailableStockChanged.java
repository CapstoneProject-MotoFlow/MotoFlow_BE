package fu.hl.g64.motorflow.inventory.event;

/**
 * GVHD 24/11: available stock of a part changed in a store, inside the transaction that posted it. Sales listens to
 * tell the store when a waiting order can be released (BR-90), after that transaction commits.
 */
public record AvailableStockChanged(long storeId, long productId) {
}
