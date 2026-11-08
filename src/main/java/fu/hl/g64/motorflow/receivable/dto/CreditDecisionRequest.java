package fu.hl.g64.motorflow.receivable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-62 step 3 and UC-63 step 3: Owner's reason for granting or revoking credit eligibility. */
public record CreditDecisionRequest(
        @NotBlank(message = "Vui lòng nhập lý do.")
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
