package fu.hl.g64.motorflow.inventory.service;

import fu.hl.g64.motorflow.inventory.dto.InventoryOperationsResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.time.YearMonth;

/** UC-88 View Inventory Operations Report. */
public interface InventoryOperationsReportService {

    /**
     * Opening, in by source, out by source and closing of each part in each warehouse for {@code month} (this month
     * when null), ordered by warehouse name then SKU. An Owner sees every warehouse or {@code storeId}, with values;
     * a Branch Manager sees their own warehouse in quantities only (BR-72). {@code q} (SKU or part name) and
     * {@code categoryId} (with its subcategories) are optional filters.
     */
    InventoryOperationsResponse report(AuthenticatedUser user, YearMonth month, Long storeId, String q,
                                       Long categoryId, int page, int size);
}
