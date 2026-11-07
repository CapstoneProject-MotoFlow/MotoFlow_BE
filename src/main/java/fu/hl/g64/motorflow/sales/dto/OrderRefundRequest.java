package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * UC-60 Step 7_Net collection exceeds the total, UC-56 Step 4_Collected money remains: money given back to the
 * customer before completion, at most what the order holds (BR-48). {@code verified} is the seller's "Đã trả tiền cho
 * khách": an unverified refund does not count until verified (BR-46). {@code expectedRefundable} is the order's
 * {@code money.refundable} the seller saw: if it changed meanwhile, for instance because the same refund was already
 * sent, nothing is recorded (MSG194). A refund sent with a cancellation is checked by the cancellation instead.
 */
public record OrderRefundRequest(
        @NotNull(message = "Vui lòng nhập số tiền hoàn.")
        @Positive(message = "Số tiền hoàn phải lớn hơn 0.")
        @Max(value = 100_000_000_000L, message = "Số tiền hoàn tối đa 100.000.000.000 đ.")
        Long amount,

        @NotNull(message = "Vui lòng chọn hình thức hoàn tiền.")
        PaymentMethod paymentMethod,

        @Size(max = 100, message = "Mã giao dịch tối đa 100 ký tự.")
        String transactionReference,

        @NotNull(message = "Vui lòng cho biết đã trả tiền cho khách hay chưa.")
        Boolean verified,

        @NotNull(message = "Vui lòng tải lại đơn để xem số tiền còn hoàn được.")
        Long expectedRefundable) {
}
