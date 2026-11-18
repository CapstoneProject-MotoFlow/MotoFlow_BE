package fu.hl.g64.motorflow.inventory.service;

import fu.hl.g64.motorflow.inventory.dto.ClosingPreviewResponse;
import fu.hl.g64.motorflow.inventory.dto.InventoryPeriodResponse;
import fu.hl.g64.motorflow.inventory.dto.PeriodBalanceResponse;

import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/** FE-13 Inventory Period Management, Owner only. */
public interface InventoryPeriodService {

    /** UC-99: every period, newest first. */
    List<InventoryPeriodResponse> list();

    /**
     * UC-99: the balances of a period by warehouse and part, ordered by warehouse name then SKU. {@code storeId},
     * {@code q} (SKU or part name) and {@code categoryId} (with its subcategories) are optional filters.
     */
    PeriodBalanceResponse balances(long periodId, Long storeId, String q, Long categoryId, int page, int size);

    /** UC-100 step 2: blocking counts, warnings and the closing values, without changing anything. */
    ClosingPreviewResponse closingPreview(long periodId);

    /**
     * UC-100: stores the period's balances and period average costs, closes it for good and opens the next month
     * (BR-84, BR-85). Only the oldest open period, once its month has ended and none of its counts is unfinished.
     */
    InventoryPeriodResponse close(AuthenticatedUser owner, long periodId);
}
