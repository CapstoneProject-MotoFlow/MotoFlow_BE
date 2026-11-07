package fu.hl.g64.motorflow.discrepancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * UC-46 Step 3_Shortage settled without replacement: Owner agreed with the supplier not to receive part or all of the
 * replacement still owed. {@code quantity} is everything no replacement delivery is bringing yet when left out.
 */
public record WaiveReplacementRequest(
        @Positive(message = "Số lượng phải lớn hơn 0.")
        @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
        Integer quantity,

        @NotBlank(message = "Vui lòng nhập lý do không giao bù.")
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
