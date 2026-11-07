package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-60 steps 3-5: money the customer pays for a confirmed order. {@code amount} is what goes to the order, at most
 * what is still payable; {@code tenderedAmount} is the cash handed over, the difference being change returned
 * (BR-47). A bank transfer needs its {@code transactionReference}, which must not be recorded already (BR-45).
 * {@code verified} is the seller's "Đã nhận đủ số tiền này": an unverified payment does not count until verified
 * (BR-46). {@code expectedPayable} is the order's {@code money.payableNow} the seller saw: if it changed meanwhile,
 * for instance because the same payment was already sent, nothing is recorded (MSG194).
 */
public record OrderPaymentRequest(
        @NotNull(message = "Vui lòng nhập số tiền ghi thu cho đơn.")
        @Positive(message = "Số tiền ghi thu phải lớn hơn 0.")
        @Max(value = 100_000_000_000L, message = "Số tiền ghi thu tối đa 100.000.000.000 đ.")
        Long amount,

        @Positive(message = "Tiền khách đưa phải lớn hơn 0.")
        @Max(value = 100_000_000_000L, message = "Tiền khách đưa tối đa 100.000.000.000 đ.")
        Long tenderedAmount,

        @NotNull(message = "Vui lòng chọn hình thức thanh toán.")
        PaymentMethod paymentMethod,

        @Size(max = 100, message = "Mã giao dịch tối đa 100 ký tự.")
        String transactionReference,

        @NotNull(message = "Vui lòng cho biết đã nhận đủ số tiền này hay chưa.")
        Boolean verified,

        @NotNull(message = "Vui lòng tải lại đơn để xem số tiền còn phải thu.")
        Long expectedPayable,

        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> evidence) {
}
