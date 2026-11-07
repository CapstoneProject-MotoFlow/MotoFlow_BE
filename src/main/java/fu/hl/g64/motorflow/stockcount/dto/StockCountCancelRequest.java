package fu.hl.g64.motorflow.stockcount.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-94: cancelling a count before it goes to the Owner. */
public record StockCountCancelRequest(
        @NotBlank(message = "Vui lòng nhập lý do hủy.")
        @Size(max = 255, message = "Lý do hủy tối đa 255 ký tự.")
        String reason) {
}
