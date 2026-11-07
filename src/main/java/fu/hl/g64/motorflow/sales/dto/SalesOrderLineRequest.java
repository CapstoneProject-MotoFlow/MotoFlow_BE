package fu.hl.g64.motorflow.sales.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** One part the customer asks for and how many (UC-53 step 4). A part on two lines is MSG158. */
public record SalesOrderLineRequest(
        @NotNull(message = "Vui lòng chọn phụ tùng.")
        Long productId,

        @NotNull(message = "Vui lòng nhập số lượng.")
        @Positive(message = "Số lượng phải lớn hơn 0.")
        @Max(value = 100_000, message = "Số lượng tối đa 100.000.")
        Integer quantity) {
}
