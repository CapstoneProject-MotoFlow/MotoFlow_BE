package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * UC-56 steps 3-5 and screen 8.2.2 "Hoàn tiền khi hủy đơn": the reason, and the refund of what the order still holds
 * when it holds anything. The order is cancelled only when nothing is left after the refund (BR-39).
 */
public record CancelSalesOrderRequest(
        @NotBlank(message = "Vui lòng nhập lý do hủy.")
        @Size(max = 255, message = "Lý do hủy tối đa 255 ký tự.")
        String reason,

        @Valid
        Refund refund) {

    /** The money given back now, which the seller has handed over ("Đã trả tiền cho khách"). */
    public record Refund(
            @NotNull(message = "Vui lòng nhập số tiền hoàn.")
            @Positive(message = "Số tiền hoàn phải lớn hơn 0.")
            @Max(value = 100_000_000_000L, message = "Số tiền hoàn tối đa 100.000.000.000 đ.")
            Long amount,

            @NotNull(message = "Vui lòng chọn hình thức hoàn tiền.")
            PaymentMethod paymentMethod,

            @Size(max = 100, message = "Mã giao dịch tối đa 100 ký tự.")
            String transactionReference,

            @NotNull(message = "Vui lòng xác nhận đã trả tiền cho khách.")
            @AssertTrue(message = "Vui lòng xác nhận đã trả tiền cho khách.")
            Boolean paidToCustomer) {
    }
}
