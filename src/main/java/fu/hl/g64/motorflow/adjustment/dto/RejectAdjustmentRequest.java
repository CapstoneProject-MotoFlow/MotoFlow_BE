package fu.hl.g64.motorflow.adjustment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-84 Step 3_Reject request: the reason is kept and sent to the requester. */
public record RejectAdjustmentRequest(
        @NotBlank(message = "Vui lòng nhập lý do từ chối.")
        @Size(max = 255, message = "Lý do từ chối tối đa 255 ký tự.")
        String reason) {
}
