package fu.hl.g64.motorflow.stockcount.service;

import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;

import java.util.List;

/**
 * The lines of a count sheet (R4 II.1). A part's lots with an expiry date are one line each; all its lots without one
 * share a single line with no lot. AVAILABLE and DAMAGED stock are counted together.
 */
public interface CountSheetService {

    /**
     * UC-91: saves the lines of a new count from its scope: WHOLE, every part with AVAILABLE or DAMAGED stock;
     * AREA, the parts whose storage area is the scope note; PRODUCTS, {@code productIds} (a listed part with no stock
     * still gets one line with no lot). Book quantities stay 0 until counting starts.
     */
    List<StockCountLine> buildLines(StockCount count, List<Long> productIds);

    /**
     * UC-93, BR-79: sets each line's book quantity to AVAILABLE + DAMAGED now, and book damaged to DAMAGED: of its lot,
     * or for a line with no lot, of the part's lots without an expiry date.
     */
    void captureBookQuantities(StockCount count, List<StockCountLine> lines);

    /** UC-94: a line needs Owner approval for a difference or a change in damaged quantity. */
    boolean needsApproval(List<StockCountLine> lines);
}
