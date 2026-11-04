package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef.SourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * UC-50: one stock movement with its lot and source document. {@code unitCost} and {@code value} are filled for the
 * Owner only (BR-72).
 */
public record StockMovementResponse(long id, LocalDateTime occurredAt, long storeId, long productId, String sku,
                                    Long lotId, MovementType movementType, StockStatus stockStatus, int quantityChange,
                                    SourceType sourceType, long sourceId, String reason, long performedBy,
                                    String performedByName, BigDecimal unitCost, Long value) {
}
