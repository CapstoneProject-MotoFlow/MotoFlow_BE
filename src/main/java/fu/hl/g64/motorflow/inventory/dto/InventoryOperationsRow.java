package fu.hl.g64.motorflow.inventory.dto;

import java.util.List;

/**
 * One part in one warehouse for a month (UC-88): closing = opening + in − out, and the in and out of
 * {@code bySource} add up to {@code inQty} and {@code outQty}. Every value is null for a Branch Manager (BR-72).
 * {@code inTransitQty} is the part of the closing quantity still on its way to this warehouse.
 */
public record InventoryOperationsRow(long storeId, String storeName, long productId, String sku, String partName,
                                     String baseUnit, long openingQty, Long openingValue, long inQty, Long inValue,
                                     long outQty, Long outValue, long closingQty, Long closingValue,
                                     long inTransitQty, Long inTransitValue, List<SourceFlow> bySource) {

    /** What one source brought in and took out; only sources with a movement are listed. */
    public record SourceFlow(StockSource source, long inQty, Long inValue, long outQty, Long outValue) {
    }
}
