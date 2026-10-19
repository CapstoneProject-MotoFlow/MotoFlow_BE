package fu.hl.g64.motorflow.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-11. The new temporary password Admin hands to the user. */
public record ResetPasswordRequest(
        @NotBlank(message = "Vui lòng nhập mật khẩu tạm.")
        String temporaryPassword,

        @Size(max = 500, message = "Lý do tối đa 500 ký tự.")
        String reason) {
}
