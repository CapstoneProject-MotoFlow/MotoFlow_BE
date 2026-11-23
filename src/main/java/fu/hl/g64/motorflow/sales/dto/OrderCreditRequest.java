package fu.hl.g64.motorflow.sales.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * UC-61 steps 3-7: the unpaid balance of the confirmed version goes on credit with the due date agreed with the
 * customer, and the order completes. {@code creditAmount} is the amount the seller saw (screen 8.2.2 "Số tiền ghi
 * nợ"), refused if the balance changed since (MSG190); {@code version} is the version the seller opened.
 * <p>
 * Plan 23/11 (3b): the customer may pay part now. {@code cashAmount}, {@code tenderedAmount}, {@code transferAmount}
 * and {@code transactionReference} work as on {@code payments/split} (MSG182, MSG154) and are recorded as verified in
 * the same transaction; {@code creditAmount} is then the balance less that prepayment, which must leave something on
 * credit (MSG347: paying it all is a payment, not credit).
 */
public record OrderCreditRequest(
        @NotNull(message = "Vui lòng tải lại đơn bán.")
        Integer version,

        @NotNull(message = "Vui lòng nhập số tiền ghi nợ.")
        @Positive(message = "Số tiền ghi nợ phải lớn hơn 0.")
        Long creditAmount,

        @NotNull(message = "Vui lòng chọn hạn trả.")
        LocalDate dueDate,

        @NotNull(message = "Vui lòng xác nhận khách hàng đồng ý số tiền và hạn trả.")
        @AssertTrue(message = "Vui lòng xác nhận khách hàng đồng ý số tiền và hạn trả.")
        Boolean customerAgreed,

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
        String transactionReference) {

    /** The earlier request, without a prepayment. */
    public OrderCreditRequest(Integer version, Long creditAmount, LocalDate dueDate, Boolean customerAgreed) {
        this(version, creditAmount, dueDate, customerAgreed, null, null, null, null);
    }

    public long cash() {
        return cashAmount == null ? 0 : cashAmount;
    }

    public long transfer() {
        return transferAmount == null ? 0 : transferAmount;
    }
}
