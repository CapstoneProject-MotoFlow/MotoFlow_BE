package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.repository.PeriodClosingQuery.OpenCount;
import fu.hl.g64.motorflow.inventory.repository.PeriodClosingQuery.OpenReceipt;
import fu.hl.g64.motorflow.inventory.repository.PeriodClosingQuery.OpenTransfer;

import java.util.List;

/**
 * UC-100 closing preview. {@code canClose} is false when the period is not the oldest open one, its month has not
 * ended or {@code blockingCounts} is not empty (BR-84); {@code reason} then says why as a message code.
 * Unconfirmed receipts and goods in transit are warnings only. {@code totals} are the closing values to be stored.
 */
public record ClosingPreviewResponse(InventoryPeriodResponse period, boolean canClose, String reason,
                                     List<OpenCount> blockingCounts, List<OpenReceipt> unconfirmedReceipts,
                                     List<OpenTransfer> transfersInTransit, int balanceRows,
                                     PeriodBalanceResponse.Totals totals) {
}
