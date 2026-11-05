package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

import java.util.List;

/**
 * Stock that leaves one status of one warehouse, for example a sale (SALE_ISSUE from AVAILABLE) or goods handed back
 * to the supplier (SUPPLIER_RETURN from AWAITING_SUPPLIER_RETURN).
 *
 * @param lots the lots the staff actually picked; null or empty lets the service pick FEFO, then FIFO
 */
public record OutPosting(
        Long storeId,
        Long productId,
        StockStatus fromStatus,
        int quantity,
        List<LotQuantity> lots,
        MovementType movementType,
        SourceRef source,
        Long performedBy,
        String reason) {
}
