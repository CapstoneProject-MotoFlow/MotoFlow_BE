package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;

import java.time.LocalDate;

/** UC-50 filters; dates are inclusive. {@code storeId} null: own warehouse, or every warehouse for the Owner. */
public record MovementSearch(Long storeId, Long productId, Long lotId, MovementType movementType, LocalDate from,
                             LocalDate to) {
}
