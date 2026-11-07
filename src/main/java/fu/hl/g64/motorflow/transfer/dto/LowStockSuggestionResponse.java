package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;

/** Screen 7.1.2 "add all parts below threshold": one part to ask for, with enough to reach the threshold again. */
public record LowStockSuggestionResponse(long productId, String sku, String partName, String baseUnit, int availableQty,
                                         int lowStockThreshold, int suggestedQty) {

    public static LowStockSuggestionResponse from(StockBalance balance, Product part) {
        return new LowStockSuggestionResponse(part.getId(), part.getSku(), part.getPartName(), part.getBaseUnit(),
                balance.getAvailableQty(), balance.getLowStockThreshold(),
                Math.max(balance.getLowStockThreshold() - balance.getAvailableQty(), 1));
    }
}
