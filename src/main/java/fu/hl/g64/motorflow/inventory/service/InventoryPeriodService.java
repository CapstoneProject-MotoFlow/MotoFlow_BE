package fu.hl.g64.motorflow.inventory.service;

import fu.hl.g64.motorflow.inventory.dto.InventoryPeriodResponse;
import fu.hl.g64.motorflow.inventory.dto.PeriodBalanceResponse;

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
}
