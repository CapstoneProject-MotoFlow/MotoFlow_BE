package fu.hl.g64.motorflow.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * GVHD 20/11: how many of each line of a waiting order to sell now. A line left out releases nothing; at most what
 * the line waits for (MSG305) and what the store can sell now is released, the rest keeps waiting.
 */
public record ReleaseWaitingOrderRequest(
        @NotEmpty(message = "Vui lòng nhập số lấy ngay.")
        @Size(max = 200, message = "Tối đa 200 dòng.")
        List<@NotNull @Valid Line> lines) {

    public record Line(
            @NotNull(message = "Vui lòng chọn dòng.")
            Long lineId,

            @NotNull(message = "Vui lòng nhập số lấy ngay.")
            @PositiveOrZero(message = "Số lấy ngay không được âm.")
            Integer nowQty) {
    }
}
