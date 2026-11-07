package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** UC-57 for an outgoing transfer: the prepared quantity of every line, sent at once. */
public record PreparedLinesRequest(
        @NotEmpty(message = "Vui lòng ghi số đã chuẩn bị cho từng phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng.")
        List<@NotNull @Valid Line> lines) {

    /** One part: what was actually taken out for the transfer, and why less than planned if so. */
    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập số đã chuẩn bị.")
            @PositiveOrZero(message = "Số đã chuẩn bị không được âm.")
            @Max(value = 100_000, message = "Số đã chuẩn bị tối đa 100.000.")
            Integer preparedQty,

            @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
            String shortReason) {
    }
}
