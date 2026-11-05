package fu.hl.g64.motorflow.receiving.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;

/**
 * One part of a PO with the quantity and purchase price agreed with the supplier (BR-19). A quantity or price not
 * greater than 0, or a part already on an earlier line, is MSG20 with the line number. The upper limits only keep the
 * PO value inside the range of a number.
 */
public record PurchaseOrderLineRequest(
        @NotNull(message = "Vui lòng chọn phụ tùng.")
        Long productId,

        @NotNull(message = "Vui lòng nhập số lượng.")
        @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000.")
        Integer quantity,

        @NotNull(message = "Vui lòng nhập giá nhập.")
        @Max(value = 10_000_000_000L, message = "Giá nhập tối đa 10.000.000.000 đ.")
        Long purchasePrice) {
}
