package fu.hl.g64.motorflow.account.dto;

import jakarta.validation.constraints.Size;

/** UC-11. The body is optional; the system generates the new temporary password. */
public record ResetPasswordRequest(
        @Size(max = 500, message = "Lý do tối đa 500 ký tự.")
        String reason) {
}
