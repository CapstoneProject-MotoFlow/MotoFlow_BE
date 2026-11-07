package fu.hl.g64.motorflow.receiving.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-42 Cancel PO: the reason is kept on the PO. */
public record CancelPurchaseOrderRequest(
        @NotBlank(message = "Vui lòng nhập lý do hủy.")
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
