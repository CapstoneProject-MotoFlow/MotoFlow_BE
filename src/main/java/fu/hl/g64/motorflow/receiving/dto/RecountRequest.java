package fu.hl.g64.motorflow.receiving.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-44 Step 3_Return for recount: why the result goes back to the person who recorded it. */
public record RecountRequest(
        @NotBlank(message = "Vui lòng nhập lý do trả về kiểm lại.")
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
