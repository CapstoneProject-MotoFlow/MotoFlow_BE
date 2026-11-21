package fu.hl.g64.motorflow.discrepancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * GVHD 20/11 (PR 5): Owner refuses the pending request of one {@code type}; the goods stay held for return or
 * write-off.
 */
public record RejectAcceptanceRequest(
        @NotNull(message = "Vui lòng chọn loại hàng.")
        AcceptanceRequest.Type type,

        @NotBlank(message = "Vui lòng nhập lý do từ chối.")
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
