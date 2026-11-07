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
 * to return to the supplier (rejected excess included), whose goods move to awaiting return to supplier, and apart
 * from it the quantity the supplier replaces (BR-27): a part may be replaced without going back. The replacement is
 * at most the case's replacementLimitQty, and at least the short quantity (only Owner waives a shortage) and what
 * was already replaced, waived or is on its way. Left empty, it grows by the wrong or damaged units newly agreed to
 * go back, within that limit.
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
            Integer returnRequiredQty,

            @PositiveOrZero(message = "Số lượng không được âm.")
            @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
            Integer replacementRequiredQty) {
    }
}
