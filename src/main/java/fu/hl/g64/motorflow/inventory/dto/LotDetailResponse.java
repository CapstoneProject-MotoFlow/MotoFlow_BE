package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-98: one lot as seen from one warehouse.
 *
 * @param state             an {@link StockDetailResponse.ExpiryState} name, or DEPLETED when nothing is left in
 *                          {@code viewStoreId}
 * @param supplierId        null for an opening or count-surplus lot
 * @param receivedQty       what first came in at {@code firstStoreId}, accepted excess included
 * @param remainingQty      what {@code viewStoreId} holds now, not counting stock in transit
 * @param movements         the latest 10 movements of the lot in {@code viewStoreId}, newest first
 * @param issueOrder        up to 20 lots of the same part in {@code viewStoreId}, in picking order (BR-75)
 * @param purchaseUnitPrice filled for the Owner only (BR-72)
 */
public record LotDetailResponse(long lotId, String state, Integer daysToExpiry, long productId, String sku,
                                String partName, String baseUnit, LotSourceType sourceType, Long supplierId,
                                String supplierName, SourceDocument sourceDocument, long firstStoreId,
                                String firstStoreName, LocalDate receivedDate, String manufacturerLotNo,
                                LocalDate manufactureDate, LocalDate expiryDate, long receivedQty, long viewStoreId,
                                int remainingQty, List<StatusQty> byStatus, List<StockMovementResponse> movements,
                                List<IssueOrderLine> issueOrder, Long purchaseUnitPrice) {

    /** The document that created the lot: GOODS_RECEIPT_NOTE or STOCK_COUNT. */
    public record SourceDocument(String type, long id) {
    }

    public record StatusQty(StockStatus stockStatus, int quantity) {
    }

    /**
     * @param current the lot on screen
     * @param skipped picking passes over it: expired, or no AVAILABLE stock
     */
    public record IssueOrderLine(long lotId, LocalDate expiryDate, LocalDate receivedDate, int availableQty,
                                 boolean current, boolean skipped) {
    }
}
