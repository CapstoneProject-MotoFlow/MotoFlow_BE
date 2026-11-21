package fu.hl.g64.motorflow.sales.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** PM 21/11: the new last day of a deposit, after today; every change is kept in the order's deposit history. */
public record DepositExpiryRequest(
        @NotNull(message = "Vui lòng chọn hạn cọc mới.")
        LocalDate depositExpiresOn) {
}
