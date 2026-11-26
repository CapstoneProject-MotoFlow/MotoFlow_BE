package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * GVHD 24/11: the destination's BM hands {@code quantity} wrong items back to the source: they came in place of
 * {@code productId} (the part ordered) and are really {@code receivedProductId}. Part may be kept on a phiếu nhận hàng
 * giao sai and part handed back.
 */
public record WrongItemReturnRequest(
        @NotNull(message = "Vui lòng chọn phụ tùng.")
        Long productId,

        @NotNull(message = "Vui lòng chọn phụ tùng thực nhận.")
        Long receivedProductId,

        @NotNull(message = "Vui lòng nhập số lượng.")
        @Min(value = 1, message = "Số lượng phải từ 1 trở lên.")
        Integer quantity,

        @Size(max = 255, message = "Ghi chú tối đa 255 ký tự.")
        String note) {
}
