package fu.hl.g64.motorflow.receivable.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-66 steps 5-6: one debt payment the collector has received and verified. {@code storeId}, {@code amount} and
 * {@code receivableIds} are as in the preview; {@code expectedAllocations} is the allocation the collector confirmed
 * from it, so the payment is refused if balances changed in between. {@code amount} is what goes to the debts;
 * {@code tenderedAmount} is the cash handed over, with the change given back kept apart (BR-47). A bank transfer needs
 * its {@code transactionReference}; a reference already recorded is refused (BR-45).
 */
public record DebtPaymentRequest(
        Long storeId,

        @NotNull(message = "Vui lòng nhập số tiền thu nợ.")
        @Positive(message = "Số tiền thu nợ phải lớn hơn 0.")
        @Max(value = 100_000_000_000L, message = "Số tiền thu nợ tối đa 100.000.000.000 đ.")
        Long amount,

        @Positive(message = "Tiền khách đưa phải lớn hơn 0.")
        @Max(value = 100_000_000_000L, message = "Tiền khách đưa tối đa 100.000.000.000 đ.")
        Long tenderedAmount,

        @NotNull(message = "Vui lòng chọn hình thức thanh toán.")
        PaymentMethod paymentMethod,

        @Size(max = 100, message = "Mã giao dịch tối đa 100 ký tự.")
        String transactionReference,

        @Size(max = 200, message = "Tối đa 200 khoản nợ.")
        List<@NotNull Long> receivableIds,

        @NotEmpty(message = "Vui lòng xem phần phân bổ trước khi ghi.")
        @Size(max = 200, message = "Tối đa 200 khoản nợ.")
        List<@NotNull @Valid AllocationLine> expectedAllocations,

        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> evidence) {

    /** One line of the confirmed preview: the receivable and the VND it receives. */
    public record AllocationLine(
            @NotNull(message = "Vui lòng nhập khoản nợ.")
            Long receivableId,

            @NotNull(message = "Vui lòng nhập số tiền phân bổ.")
            @Positive(message = "Số tiền phân bổ phải lớn hơn 0.")
            Long amount) {
    }
}
