package fu.hl.g64.motorflow.inventory.service;

import fu.hl.g64.motorflow.inventory.dto.InventoryOperationsResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.time.LocalDate;
import java.time.YearMonth;

/** UC-88 View Inventory Operations Report. */
public interface InventoryOperationsReportService {

    /**
     * Opening, in by source, out by source and closing of each part in each warehouse, ordered by warehouse name then
     * SKU: from {@code from} to {@code to} when both are given (at most 366 days, MSG264), otherwise for
     * {@code month} (this month when null); a month together with dates is MSG02. An Owner sees every warehouse or
     * {@code storeId}, with values; a Branch Manager sees their own warehouse in quantities only (BR-72). {@code q}
     * (SKU or part name) and {@code categoryId} (with its subcategories) are optional filters.
     */
    InventoryOperationsResponse report(AuthenticatedUser user, YearMonth month, LocalDate from, LocalDate to,
                                       Long storeId, String q, Long categoryId, int page, int size);
}
