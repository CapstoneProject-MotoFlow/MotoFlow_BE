package fu.hl.g64.motorflow.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
        @NotBlank(message = "Vui lòng nhập mật khẩu hiện tại.") String currentPassword,
        @NotBlank(message = "Vui lòng nhập mật khẩu mới.") String newPassword) {
}
