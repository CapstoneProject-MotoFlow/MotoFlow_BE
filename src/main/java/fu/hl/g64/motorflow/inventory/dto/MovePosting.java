package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

import java.util.List;

/**
 * The same goods change status or warehouse, lot by lot; writes a minus and a plus movement with the same unit cost.
 * Examples: transfer dispatch (source AVAILABLE to destination IN_TRANSIT), transfer receipt (destination IN_TRANSIT
 * to AVAILABLE), rejected excess (AVAILABLE to AWAITING_SUPPLIER_RETURN).
 *
 * @param lots the lots to move; null or empty lets the service pick FEFO, then FIFO
 */
public record MovePosting(
        Long productId,
        Long fromStoreId,
        StockStatus fromStatus,
        Long toStoreId,
        StockStatus toStatus,
        int quantity,
        List<LotQuantity> lots,
        MovementType movementType,
        SourceRef source,
        Long performedBy,
        String reason) {
}
