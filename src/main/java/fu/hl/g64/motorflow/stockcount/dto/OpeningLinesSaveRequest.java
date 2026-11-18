package fu.hl.g64.motorflow.stockcount.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-93, BR-86: every line of an opening count, typed or read from Excel by the client; it replaces the lines saved
 * before. One line per part and expiry date; {@code expiryDate} is null for goods without one.
 */
public record OpeningLinesSaveRequest(
        @NotNull(message = "Vui lòng gửi danh sách dòng.")
        List<@NotNull @Valid Line> lines) {

    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            LocalDate expiryDate,

            @NotNull(message = "Vui lòng nhập số đếm.")
            @Min(value = 0, message = "Số đếm không được âm.")
            Integer countedQty,

            @Min(value = 0, message = "Số hàng hỏng không được âm.")
            Integer damagedQty) {
    }
}
