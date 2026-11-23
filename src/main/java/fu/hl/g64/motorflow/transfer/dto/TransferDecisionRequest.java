package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * UC-76, PM 23/11: how the receiving store's BM (or the Owner) handles a short part: it comes in a later delivery, or
 * it is not needed any more and goes back to the source's stock when the order closes. What nobody decides on is
 * written off as lost on closing. RETURN_TO_SOURCE and LOSS are no longer accepted (MSG345); {@code
 * adjustmentRequestId} stays for requests of older clients and is ignored.
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
        NO_REPLACEMENT,
        /** Before PM 23/11; refused (MSG345). */
        RETURN_TO_SOURCE,
        /** Before PM 23/11; refused (MSG345). */
        LOSS
    }
}
