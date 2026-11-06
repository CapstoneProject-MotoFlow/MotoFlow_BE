package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** UC-68. {@code storeId}: required for the Owner; a Branch Manager always requests for their own store. */
public record ReplenishmentCreateRequest(
        Long storeId,

        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự.")
        String note,

        @NotEmpty(message = "Vui lòng thêm ít nhất một phụ tùng.")
        List<@NotNull @Valid Line> lines) {

    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập số lượng.")
            @Min(value = 1, message = "Số lượng phải từ 1 trở lên.")
            Integer requestedQty) {
    }
}
