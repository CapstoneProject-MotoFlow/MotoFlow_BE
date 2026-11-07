package fu.hl.g64.motorflow.stockcount.service;

import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;

import java.util.List;

/**
 * The lines of a count sheet (R4 II.1). A part's lots with an expiry date are one line each; all its lots without one
 * share a single line with no lot. Only AVAILABLE stock is counted.
 */
public interface CountSheetService {

    /**
     * UC-91: saves the lines of a new count from its scope: WHOLE, every part with AVAILABLE stock in the warehouse;
     * AREA, the parts whose storage area is the scope note; PRODUCTS, {@code productIds} (a listed part with no stock
     * still gets one line with no lot). Book quantities stay 0 until counting starts.
     */
    List<StockCountLine> buildLines(StockCount count, List<Long> productIds);

    /**
     * UC-93, BR-79: sets each line's book quantity to the AVAILABLE quantity now: of its lot, or for a line with no
     * lot, of the part's lots without an expiry date.
     */
    void captureBookQuantities(StockCount count, List<StockCountLine> lines);

    /** UC-94: some line was counted at another quantity than its book quantity. */
    boolean hasDifference(List<StockCountLine> lines);
}
