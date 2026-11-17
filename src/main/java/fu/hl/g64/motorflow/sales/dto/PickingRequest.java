package fu.hl.g64.motorflow.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-57: the picked quantity of every line of the sales order, sent at once (screen 8.2.1). {@code version} is the
 * version the picker opened: a version asking for other parts or quantities is refused (MSG165), one that only
 * changed the price or the customer is not.
 */
public record PickingRequest(
        @NotNull(message = "Vui lòng tải lại đơn bán.")
        Integer version,

        @NotEmpty(message = "Vui lòng ghi số đã lấy cho từng phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng.")
        List<@NotNull @Valid Line> lines) {

    /**
     * One part: what was actually taken, and the picker's note: why less than needed if so (UC-57 "Short or unsuitable
     * goods"), or any remark on a line picked in full. The note is kept on every line.
     */
    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập số đã lấy.")
            @PositiveOrZero(message = "Số đã lấy không được âm.")
            @Max(value = 100_000, message = "Số đã lấy tối đa 100.000.")
            Integer pickedQty,

            @Size(max = 255, message = "Ghi chú tối đa 255 ký tự.")
            String shortReason) {
    }
}
