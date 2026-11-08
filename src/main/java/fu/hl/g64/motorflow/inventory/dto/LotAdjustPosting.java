package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

import java.time.LocalDateTime;

/**
 * Plus or minus on one known lot, valued at the moving average: an approved count difference (COUNT_ADJUSTMENT)
 * or an approved loss or correction (ADJUSTMENT).
 *
 * @param periodId   COUNT_ADJUSTMENT only: the period to post into instead of the current month's (BR-83), must be
 *                   OPEN; null together with {@code occurredAt} keeps the current month
 * @param occurredAt COUNT_ADJUSTMENT only: the movement time, normally the end of the count day
 */
public record LotAdjustPosting(
        Long storeId,
        Long lotId,
        StockStatus status,
        int quantityChange,
        MovementType movementType,
        SourceRef source,
        Long performedBy,
        String reason,
        Long periodId,
        LocalDateTime occurredAt) {

    /** The earlier signature: posts into the current month's period. */
    public LotAdjustPosting(Long storeId, Long lotId, StockStatus status, int quantityChange,
                            MovementType movementType, SourceRef source, Long performedBy, String reason) {
        this(storeId, lotId, status, quantityChange, movementType, source, performedBy, reason, null, null);
    }
}
