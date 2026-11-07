package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * UC-76: how a coordinator handles a part still missing. {@code adjustmentRequestId}: the STOCK_LOSS request of this
 * order that writes the quantity off transit, required for LOSS.
 */
public record TransferDecisionRequest(
        @NotNull(message = "Vui lòng chọn phụ tùng.")
        Long productId,

        @NotNull(message = "Vui lòng chọn cách xử lý.")
        Decision decision,

        @NotNull(message = "Vui lòng nhập số lượng.")
        @Min(value = 1, message = "Số lượng phải từ 1 trở lên.")
        Integer quantity,

        Long adjustmentRequestId,

        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {

    public enum Decision {
        LATE_DELIVERY,
        RETURN_TO_SOURCE,
        LOSS
    }
}
