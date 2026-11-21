package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * PM 21/11: a deposit, first or additional, on a waiting order of a customer with a profile. {@code amount},
 * {@code tenderedAmount}, {@code paymentMethod}, {@code transactionReference}, {@code verified} and {@code evidence}
 * work as for an order payment (BR-45 to BR-47). {@code depositExpiresOn} is required with the first deposit and must
 * be after today; later deposits leave it out (the Branch Manager changes it with PATCH deposit-expiry).
 * {@code expectedDepositRecorded} is {@code deposit.total + deposit.unverified} as the seller saw it: if it changed
 * meanwhile, for instance because the same deposit was already sent, nothing is recorded (MSG194).
 */
public record DepositPaymentRequest(
        @NotNull(message = "Vui lòng nhập số tiền cọc.")
        @Positive(message = "Số tiền cọc phải lớn hơn 0.")
        @Max(value = 100_000_000_000L, message = "Số tiền cọc tối đa 100.000.000.000 đ.")
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

        LocalDate depositExpiresOn,

        @NotNull(message = "Vui lòng tải lại đơn để xem số tiền cọc đã ghi.")
        @PositiveOrZero(message = "Số tiền cọc đã ghi không được âm.")
        Long expectedDepositRecorded,

        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> evidence) {
}
