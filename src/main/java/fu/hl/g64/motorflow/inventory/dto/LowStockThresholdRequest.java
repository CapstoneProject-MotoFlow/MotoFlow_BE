package fu.hl.g64.motorflow.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** UC-51. Null removes the threshold. */
public record LowStockThresholdRequest(
        @Min(value = 0, message = "Ngưỡng tồn tối thiểu không được âm.")
        @Max(value = 1_000_000, message = "Ngưỡng tồn tối thiểu quá lớn.")
        Integer lowStockThreshold) {
}
