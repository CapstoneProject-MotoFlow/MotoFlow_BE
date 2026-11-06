package fu.hl.g64.motorflow.pricing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * UC-35: the new chain-wide retail price of one part and the day it applies from (today or later). With
 * {@code fromSuggestion} the price is the suggested price the user saw, applied as the new price (BR-08, BR-69).
 */
public record RetailPriceRequest(
        @NotNull(message = "Vui lòng chọn phụ tùng.")
        Long productId,

        @NotNull(message = "Vui lòng nhập giá bán lẻ mới.")
        @Positive(message = "Giá bán lẻ phải lớn hơn 0.")
        Long unitPrice,

        @NotNull(message = "Vui lòng chọn ngày áp dụng.")
        LocalDate effectiveDate,

        @Size(max = 500, message = "Lý do tối đa 500 ký tự.")
        String reason,

        boolean fromSuggestion) {
}
