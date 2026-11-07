package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

import java.util.Map;

/**
 * More stock on a lot that a supplier receipt already created, at that lot's purchase price: the excess of a GRN line
 * that Owner accepts (AVAILABLE; BR-73, excess accepted by Owner joins the lot of the same line) or rejects
 * (AWAITING_SUPPLIER_RETURN; BR-67). Only the AVAILABLE quantity enters the moving average (BR-74).
 *
 * @param source the GRN
 */
public record LotReceiptPosting(
        Long storeId,
        Long lotId,
        Map<StockStatus, Integer> quantities,
        MovementType movementType,
        SourceRef source,
        Long performedBy,
        String reason) {
}
