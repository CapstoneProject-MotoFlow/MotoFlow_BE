package fu.hl.g64.motorflow.discrepancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * GVHD 20/11 (PR 5), Owner only: {@code quantity} dented units of the case come into stock as the part's "-M" variant
 * at {@code unitCost}. {@code salePrice} becomes the variant's retail price from today; it is required the first time
 * (the variant has no price yet, MSG311) and left out keeps the price in effect.
 */
public record AcceptAsVariantRequest(
        @NotNull(message = "Vui lòng nhập số lượng.")
        @Positive(message = "Số lượng phải lớn hơn 0.")
        @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
        Integer quantity,

        @NotNull(message = "Vui lòng nhập giá vốn.")
        @PositiveOrZero(message = "Giá vốn không được âm.")
        Long unitCost,

        @Positive(message = "Giá bán phải lớn hơn 0.")
        Long salePrice) {
}
