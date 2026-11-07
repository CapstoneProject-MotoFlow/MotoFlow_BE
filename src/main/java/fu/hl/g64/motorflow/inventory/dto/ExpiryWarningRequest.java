package fu.hl.g64.motorflow.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** UC-104: how many days before expiry a lot counts as near expiry, and the day it applies from (today or later). */
public record ExpiryWarningRequest(
        @NotNull(message = "Vui lòng nhập số ngày cảnh báo.")
        @Min(value = 1, message = "Số ngày cảnh báo phải từ 1 đến 365.")
        @Max(value = 365, message = "Số ngày cảnh báo phải từ 1 đến 365.")
        Integer days,

        @NotNull(message = "Vui lòng chọn ngày áp dụng.")
        LocalDate effectiveDate) {
}
