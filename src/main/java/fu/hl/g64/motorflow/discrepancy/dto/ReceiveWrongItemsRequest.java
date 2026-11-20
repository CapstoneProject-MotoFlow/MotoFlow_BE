package fu.hl.g64.motorflow.discrepancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * GVHD 20/11 (PR 5), Owner only: {@code quantity} wrong units of the case come into stock as part {@code productId}
 * (on sale, not the part ordered) at {@code unitCost}. Without {@code replacementNeeded} the replacement still owed for
 * them is waived in the same step.
 */
public record ReceiveWrongItemsRequest(
        @NotNull(message = "Vui lòng chọn phụ tùng thực nhận.")
        Long productId,

        @NotNull(message = "Vui lòng nhập số lượng.")
        @Positive(message = "Số lượng phải lớn hơn 0.")
        @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
        Integer quantity,

        @NotNull(message = "Vui lòng nhập giá vốn.")
        @PositiveOrZero(message = "Giá vốn không được âm.")
        Long unitCost,

        boolean replacementNeeded) {
}
