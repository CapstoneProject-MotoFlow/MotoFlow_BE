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
 * UC-55: the whole new content of the order, as in {@link SalesOrderRequest} without the store. {@code version} is the
 * version the seller opened; if someone saved another one since, the change is refused (MSG165). A change of
 * {@code note} alone saves no new version and keeps the confirmation; leaving it out or blank clears the note.
 */
public record SalesOrderUpdateRequest(
        @NotNull(message = "Vui lòng tải lại đơn bán.")
        Integer version,

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

        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự.")
        String note,

        @NotEmpty(message = "Vui lòng chọn ít nhất một phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng.")
        List<@NotNull @Valid SalesOrderLineRequest> lines) {
}
