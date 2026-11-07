package fu.hl.g64.motorflow.stockcount.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-92: one stock count. What a line shows depends on the viewer (see {@code StockCountMapper}): the counter sees
 * no book quantity, difference or reason; only the Owner sees the value of a difference (BR-72).
 *
 * @param request             the Owner's request the count answers, or null
 * @param awaitingBookQuantities lines came back for a recount and the counter has not started again yet
 */
public record StockCountDetailResponse(
        long id,
        long storeId,
        String storeName,
        Long inventoryPeriodId,
        CountType countType,
        LocalDate countDate,
        ScopeType scopeType,
        String scopeNote,
        StockCountStatus status,
        long createdBy,
        String createdByName,
        LocalDateTime createdAt,
        LocalDateTime countingStartedAt,
        Long confirmedBy,
        LocalDateTime confirmedAt,
        Long approvedBy,
        LocalDateTime approvedAt,
        String decisionReason,
        Long cancelledBy,
        LocalDateTime cancelledAt,
        String cancellationReason,
        boolean awaitingBookQuantities,
        CountRequestResponse request,
        List<Line> lines) {

    /**
     * @param bookQty         null for the counter
     * @param difference      counted minus book; null while uncounted, and for the counter
     * @param differenceValue difference at the current moving average cost; Owner only, null otherwise
     * @param reason          why the count differs (Branch Manager); null for the counter
     */
    public record Line(
            long id,
            long productId,
            String sku,
            String partName,
            String baseUnit,
            Long lotId,
            LocalDate expiryDate,
            String storageArea,
            String binLocation,
            Integer bookQty,
            Integer countedQty,
            Integer difference,
            BigDecimal differenceValue,
            String reason,
            boolean recountRequested,
            String recountReason,
            Integer previousBookQty,
            Integer previousCountedQty,
            LocalDateTime countedAt) {
    }
}
