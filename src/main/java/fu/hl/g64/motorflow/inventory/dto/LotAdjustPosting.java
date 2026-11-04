package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

/**
 * Plus or minus on one known lot, valued at the moving average: an approved count difference (COUNT_ADJUSTMENT)
 * or an approved loss or correction (ADJUSTMENT).
 */
public record LotAdjustPosting(
        Long storeId,
        Long lotId,
        StockStatus status,
        int quantityChange,
        MovementType movementType,
        SourceRef source,
        Long performedBy,
        String reason) {
}
