package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;

import java.util.List;

/**
 * Inventory Operations Report for one month (UC-88). {@code periodStatus} is null for a month with no movement,
 * which has no period; {@code provisional} is true unless the month's period is closed. {@code withValues} is false
 * for a Branch Manager, who sees quantities only (BR-72); {@code totals} is then null.
 */
public record InventoryOperationsResponse(String month, InventoryPeriodStatus periodStatus, boolean provisional,
                                          boolean withValues, Totals totals,
                                          PageResponse<InventoryOperationsRow> page) {

    /** Values of every row that matches the filter, not only this page; quantities of different parts do not add up. */
    public record Totals(long openingValue, long inValue, long outValue, long closingValue, long inTransitValue,
                         List<SourceTotal> bySource) {
    }

    public record SourceTotal(StockSource source, long inValue, long outValue) {
    }
}
