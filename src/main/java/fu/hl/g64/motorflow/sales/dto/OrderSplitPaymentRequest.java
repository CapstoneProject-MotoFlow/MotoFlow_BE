package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-60 steps 3-5, góp ý UI 21/11: one collection of a confirmed order, split into cash and bank transfer. The
 * seller types what was received of each, so both are recorded as verified (no "Đã nhận đủ số tiền này" any more).
 * {@code cashAmount} + {@code transferAmount} must be above 0 and at most what is still payable (MSG333, MSG181).
 * {@code tenderedAmount} is the cash handed over, at least {@code cashAmount} (BR-47, MSG182). A transfer needs its
 * {@code transactionReference}, not recorded already (MSG154, BR-45). {@code expectedPayable} is the order's
 * {@code money.payableNow} the seller saw (MSG194). Evidence files go with the transfer, or the cash if there is none.
 */
public record OrderSplitPaymentRequest(
        @PositiveOrZero(message = "Tiền mặt không được âm.")
        @Max(value = 100_000_000_000L, message = "Tiền mặt tối đa 100.000.000.000 đ.")
        Long cashAmount,

        @PositiveOrZero(message = "Tiền khách đưa không được âm.")
        @Max(value = 100_000_000_000L, message = "Tiền khách đưa tối đa 100.000.000.000 đ.")
        Long tenderedAmount,

        @PositiveOrZero(message = "Tiền chuyển khoản không được âm.")
        @Max(value = 100_000_000_000L, message = "Tiền chuyển khoản tối đa 100.000.000.000 đ.")
        Long transferAmount,

        @Size(max = 100, message = "Mã giao dịch tối đa 100 ký tự.")
        String transactionReference,

        @NotNull(message = "Vui lòng tải lại đơn để xem số tiền còn phải thu.")
        Long expectedPayable,

        @Size(max = 10, message = "Tối đa 10 tệp.")
        List<@NotNull @Valid AttachmentUpload> evidence) {

    public long cash() {
        return cashAmount == null ? 0 : cashAmount;
    }

    public long transfer() {
        return transferAmount == null ? 0 : transferAmount;
    }
}
