package fu.hl.g64.motorflow.inventory.dto;

/**
 * UC-47: how many stock rows in the filtered scope fall into each group; a row can be in several.
 *
 * @param withStock   rows with stock on hand in any status, goods in transit not included
 * @param lowStock    rows below their low-stock threshold
 * @param unavailable rows with stock awaiting inspection, wrong, damaged or awaiting supplier return
 * @param expiryAlert rows with available stock in lots expired or in the warning window
 */
public record StockSummaryResponse(long withStock, long lowStock, long unavailable, long expiryAlert) {
}
