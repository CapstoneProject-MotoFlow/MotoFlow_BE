package fu.hl.g64.motorflow.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-58 steps 3-5: the quantity to deliver of every line, from what was picked, once the customer agrees. Delivering
 * less than requested saves a new version, priced again (BR-36). {@code version} is the version the seller opened
 * (MSG165 if another was saved since).
 */
public record ConfirmSalesOrderRequest(
        @NotNull(message = "Vui lòng tải lại đơn bán.")
        Integer version,

        @NotNull(message = "Vui lòng xác nhận khách hàng đồng ý nhận hàng.")
        @AssertTrue(message = "Vui lòng xác nhận khách hàng đồng ý nhận hàng.")
        Boolean customerAgreed,

        @NotEmpty(message = "Vui lòng nhập số giao cho từng phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng.")
        List<@NotNull @Valid Line> lines) {

    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập số giao.")
            @PositiveOrZero(message = "Số giao không được âm.")
            @Max(value = 100_000, message = "Số giao tối đa 100.000.")
            Integer deliveredQty) {
    }
}
