package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-44 Step 3_Return for recount. */
public record RecountRequest(
        @NotBlank(message = "Vui lòng nhập lý do kiểm lại.")
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
