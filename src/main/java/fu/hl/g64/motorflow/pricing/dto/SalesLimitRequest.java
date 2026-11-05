package fu.hl.g64.motorflow.pricing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/** UC-38: a new discount threshold or minimum margin, in percent, and the day it applies from (today or later). */
public record SalesLimitRequest(
        @NotNull(message = "Vui lòng chọn chính sách cần lưu.")
        SalesLimitType limitType,

        @NotNull(message = "Vui lòng nhập tỷ lệ.")
        @DecimalMin(value = "0", message = "Tỷ lệ phải từ 0 đến 100%.")
        @DecimalMax(value = "100", message = "Tỷ lệ phải từ 0 đến 100%.")
        @Digits(integer = 3, fraction = 2, message = "Tỷ lệ có tối đa 2 chữ số thập phân.")
        BigDecimal rate,

        @NotNull(message = "Vui lòng chọn ngày áp dụng.")
        LocalDate effectiveDate) {
}
