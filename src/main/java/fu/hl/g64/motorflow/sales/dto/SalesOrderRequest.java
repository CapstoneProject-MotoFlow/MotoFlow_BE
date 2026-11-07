package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * UC-53: a new sales order, also used to preview its prices before saving. {@code storeId} is the selling store:
 * Owner must choose it (MSG157), a Branch Manager sells at their own store and may leave it out. Without
 * {@code customerId} it is a walk-in sale, whose name and phone may be recorded. {@code discountRate} is one rate in
 * percent for the whole order and only for a wholesale order (BR-10, BR-12; MSG161 for a retail order).
 */
public record SalesOrderRequest(
        Long storeId,

        Long customerId,

        @Size(max = 150, message = "Tên khách lẻ tối đa 150 ký tự.")
        String walkInName,

        @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự.")
        String walkInPhone,

        @NotNull(message = "Vui lòng chọn loại giá.")
        PriceType priceType,

        @DecimalMin(value = "0", message = "Chiết khấu không được âm.")
        @DecimalMax(value = "100", inclusive = false, message = "Chiết khấu phải nhỏ hơn 100%.")
        @Digits(integer = 2, fraction = 2, message = "Chiết khấu có tối đa 2 chữ số thập phân.")
        BigDecimal discountRate,

        @NotEmpty(message = "Vui lòng chọn ít nhất một phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng.")
        List<@NotNull @Valid SalesOrderLineRequest> lines) {
}
