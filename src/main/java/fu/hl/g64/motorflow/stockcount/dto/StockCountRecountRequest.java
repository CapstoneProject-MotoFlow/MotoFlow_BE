package fu.hl.g64.motorflow.stockcount.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** UC-94: lines the Branch Manager or the Owner sends back to the counter. */
public record StockCountRecountRequest(
        @NotEmpty(message = "Vui lòng chọn ít nhất một dòng cần đếm lại.")
        List<@NotNull Long> lineIds,

        @NotBlank(message = "Vui lòng nhập lý do đếm lại.")
        @Size(max = 255, message = "Lý do đếm lại tối đa 255 ký tự.")
        String reason) {
}
