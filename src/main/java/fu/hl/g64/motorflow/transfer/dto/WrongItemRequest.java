package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Plan 23/11 (phiếu A): the destination's BM records that {@code quantity} wrong items received in place of
 * {@code productId} are really {@code receivedProductId}. {@code expiryDate}: what is printed on them, for parts with an
 * expiry; the Owner's confirmation takes the source lot of that expiry, or the oldest lots without one.
 */
public record WrongItemRequest(
        @NotNull(message = "Vui lòng chọn phụ tùng.")
        Long productId,

        @NotNull(message = "Vui lòng chọn phụ tùng thực nhận.")
        Long receivedProductId,

        @NotNull(message = "Vui lòng nhập số lượng.")
        @Min(value = 1, message = "Số lượng phải từ 1 trở lên.")
        Integer quantity,

        LocalDate expiryDate,

        @Size(max = 255, message = "Ghi chú tối đa 255 ký tự.")
        String reason) {
}
