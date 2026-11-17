package fu.hl.g64.motorflow.adjustment.dto;

import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;

import java.time.LocalDate;
import java.util.List;

/**
 * What a request would do (UC-83 step 5, UC-81 step 5); nothing is saved. {@code postingDate} is today: an approved
 * request posts on the day it is applied, in the open period (BR-65). {@code totalValue} is Owner only (BR-72).
 */
public record AdjustmentPreview(
        AdjustmentType adjustmentType,
        Long storeId,
        String storeName,
        AdjustmentSource source,
        LocalDate postingDate,
        List<AdjustmentLineView> stockLines,
        List<AdjustmentAmountView> amountLines,
        Long totalValue) {
}
