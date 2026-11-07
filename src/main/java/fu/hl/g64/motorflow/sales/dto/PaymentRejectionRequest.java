package fu.hl.g64.motorflow.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-56 Step 4_Unverified transaction: why a recorded payment or refund does not count. */
public record PaymentRejectionRequest(
        @NotBlank(message = "Vui lòng nhập lý do từ chối.")
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
