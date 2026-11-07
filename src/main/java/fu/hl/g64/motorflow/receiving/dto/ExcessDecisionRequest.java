package fu.hl.g64.motorflow.receiving.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** UC-45: Owner accepts or rejects the excess of every line of a confirmed GRN that still waits for a decision. */
public record ExcessDecisionRequest(
        @NotEmpty(message = "Vui lòng chọn nhận hoặc không nhận phần hàng thừa của từng dòng.")
        @Size(max = 500, message = "Tối đa 500 dòng.")
        List<@NotNull @Valid Line> lines) {

    public record Line(
            @NotNull(message = "Vui lòng chọn dòng của phiếu nhận hàng.")
            Long lineId,

            @NotNull(message = "Vui lòng chọn nhận hoặc không nhận phần hàng thừa.")
            Choice decision) {
    }

    /** ACCEPTED adds the excess to the line's lot (BR-73); REJECTED puts it in awaiting return to supplier (BR-67). */
    public enum Choice {
        ACCEPTED,
        REJECTED
    }
}
