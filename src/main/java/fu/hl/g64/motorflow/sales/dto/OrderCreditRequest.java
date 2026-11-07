package fu.hl.g64.motorflow.sales.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * UC-61 steps 3-7: the unpaid balance of the confirmed version goes on credit with the due date agreed with the
 * customer, and the order completes. {@code creditAmount} is the amount the seller saw (screen 8.2.2 "Số tiền ghi
 * nợ"), refused if the balance changed since (MSG190); {@code version} is the version the seller opened.
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
        Boolean customerAgreed) {
}
