package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

import java.util.List;
import java.util.Map;

/**
 * Like {@link MovePosting}, but the goods land in several statuses: a transfer receipt puts what passed into AVAILABLE
 * and what arrived damaged into DAMAGED. Each lot leaves {@code fromStatus} in one minus movement, so a lot split over
 * two statuses does not write the same movement key twice.
 *
 * @param toQuantities how many units go into each status; together they equal the quantities of {@code lots}
 * @param lots         the exact lots to move, in the order they fill the statuses
 */
public record SplitMovePosting(
        Long productId,
        Long fromStoreId,
        StockStatus fromStatus,
        Long toStoreId,
        Map<StockStatus, Integer> toQuantities,
        List<LotQuantity> lots,
        MovementType movementType,
        SourceRef source,
        Long performedBy,
        String reason) {
}
