package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * GVHD 20/11: how many of each line of a waiting order to sell now. A line left out releases nothing; at most what
 * the line waits for (MSG305) and what the store can sell now is released, the rest keeps waiting.
 * <p>
 * PM 21/11 (BE-5): {@code depositAmount} is the part of the deposit put into the order the release opens, from 0 to
 * the deposit left and the order's total (MSG327); null is 0. The last release, which leaves nothing waiting, puts in
 * all the deposit left up to the total (MSG326 when another amount is sent), and gives the rest back to the customer
 * as {@code depositRefund} (MSG328 when it is missing).
 */
public record ReleaseWaitingOrderRequest(
        @NotEmpty(message = "Vui lòng nhập số lấy ngay.")
        @Size(max = 200, message = "Tối đa 200 dòng.")
        List<@NotNull @Valid Line> lines,

        @PositiveOrZero(message = "Tiền cọc dùng cho đơn không được âm.")
        Long depositAmount,

        @Valid
        DepositRefund depositRefund) {

    public ReleaseWaitingOrderRequest(List<Line> lines) {
        this(lines, null, null);
    }

    public record Line(
            @NotNull(message = "Vui lòng chọn dòng.")
            Long lineId,

            @NotNull(message = "Vui lòng nhập số lấy ngay.")
            @PositiveOrZero(message = "Số lấy ngay không được âm.")
            Integer nowQty) {
    }

    /** The deposit above the order's total, handed back to the customer now ("Đã trả tiền cho khách"). */
    public record DepositRefund(
            @NotNull(message = "Vui lòng chọn hình thức trả lại tiền cọc.")
            PaymentMethod paymentMethod,

            @Size(max = 100, message = "Mã giao dịch tối đa 100 ký tự.")
            String transactionReference,

            @NotNull(message = "Vui lòng xác nhận đã trả tiền cho khách.")
            @AssertTrue(message = "Vui lòng xác nhận đã trả tiền cho khách.")
            Boolean paidToCustomer) {
    }
}
