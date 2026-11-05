package fu.hl.g64.motorflow.discrepancy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-46 steps 3-4: the handling agreed with the supplier for the cases of one receipt. For each case, the quantity
 * to return to the supplier (rejected excess included); the goods to return move to awaiting return to supplier.
 * Everything short or returned is to be replaced (BR-27) unless Owner waives it.
 */
public record DiscrepancyHandlingRequest(
        @NotEmpty(message = "Vui lòng nhập hướng xử lý cho ít nhất một phụ tùng.")
        @Size(max = 500, message = "Tối đa 500 dòng.")
        List<@NotNull @Valid Line> lines,

        @Size(max = 255, message = "Ghi chú tối đa 255 ký tự.")
        String note) {

    public record Line(
            @NotNull(message = "Vui lòng chọn hồ sơ sai lệch.")
            Long caseId,

            @NotNull(message = "Vui lòng nhập số lượng sẽ trả lại.")
            @PositiveOrZero(message = "Số lượng không được âm.")
            @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
            Integer returnRequiredQty) {
    }
}
