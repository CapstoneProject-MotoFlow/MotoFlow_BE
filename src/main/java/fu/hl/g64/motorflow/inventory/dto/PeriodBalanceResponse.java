package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.common.dto.PageResponse;

/**
 * Period Balance (UC-99). {@code provisional} is true for an open period, whose figures are worked out from the
 * ledger now and may still change; a closed period shows the balances stored when it was closed.
 * {@code totals} add up every row that matches the filter, not only this page.
 */
public record PeriodBalanceResponse(InventoryPeriodResponse period, boolean provisional, Totals totals,
                                    PageResponse<PeriodBalanceRow> page) {

    /** Values only: quantities of different parts do not add up. */
    public record Totals(long openingValue, long inValue, long outValue, long closingValue, long inTransitValue) {
    }
}
