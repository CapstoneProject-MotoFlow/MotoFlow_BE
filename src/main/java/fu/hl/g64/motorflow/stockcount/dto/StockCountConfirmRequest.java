package fu.hl.g64.motorflow.stockcount.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-94: the Branch Manager confirms the result. {@code removedLineIds}: uncounted lines left out of the count (never
 * filled with 0, BR-80). {@code reasons}: one per line whose count differs from the book.
 */
public record StockCountConfirmRequest(
        List<@NotNull Long> removedLineIds,
        List<@NotNull @Valid Reason> reasons) {

    public record Reason(
            @NotNull(message = "Vui lòng chọn dòng.")
            Long lineId,

            @Size(max = 255, message = "Lý do chênh lệch tối đa 255 ký tự.")
            String reason) {
    }
}
