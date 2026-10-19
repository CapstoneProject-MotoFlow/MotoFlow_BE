package fu.hl.g64.motorflow.account.dto;

import jakarta.validation.constraints.Size;

/** UC-10. The body is optional. */
public record LockAccountRequest(
        @Size(max = 500, message = "Lý do tối đa 500 ký tự.")
        String reason) {
}
