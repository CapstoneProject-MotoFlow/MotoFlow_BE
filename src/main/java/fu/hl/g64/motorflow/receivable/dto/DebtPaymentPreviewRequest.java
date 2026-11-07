package fu.hl.g64.motorflow.receivable.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-66 step 4: how a debt payment would be allocated (BR-70). {@code storeId} is the store collecting the money; a
 * Branch Manager may leave it out for their own store, Owner must choose one. {@code receivableIds} are the debts the
 * customer names; left out or empty, every debt still owed at that store.
 */
public record DebtPaymentPreviewRequest(
        Long storeId,

        @NotNull(message = "Vui lòng nhập số tiền thu nợ.")
        @Positive(message = "Số tiền thu nợ phải lớn hơn 0.")
        @Max(value = 100_000_000_000L, message = "Số tiền thu nợ tối đa 100.000.000.000 đ.")
        Long amount,

        @Size(max = 200, message = "Tối đa 200 khoản nợ.")
        List<@NotNull Long> receivableIds) {
}
