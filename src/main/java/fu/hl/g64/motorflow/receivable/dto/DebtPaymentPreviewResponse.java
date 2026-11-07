package fu.hl.g64.motorflow.receivable.dto;

import fu.hl.g64.motorflow.receivable.entity.enums.OriginType;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-66 step 4: the allocation of {@code amount} (VND) over the eligible debts, in the order they are settled (BR-70):
 * opening debts first, then credit sales by completion time and order number, each in full before the next. Debts
 * the payment does not reach are listed with an allocated amount of 0. {@code eligibleTotal} is the most the payment
 * may be (BR-47).
 */
public record DebtPaymentPreviewResponse(long customerId, long storeId, String storeName, long amount,
                                         long eligibleTotal, List<Line> allocations) {

    public record Line(long receivableId, OriginType originType, Long salesOrderId, LocalDate dueDate, boolean overdue,
                       long remainingBefore, long allocatedAmount, long remainingAfter) {
    }
}
