package fu.hl.g64.motorflow.receivable.dto;

import fu.hl.g64.motorflow.partner.entity.enums.CreditStatus;

import java.time.LocalDate;

/**
 * A row of the customer debt list (UC-64), amounts in VND. {@code totalDebt} is owed to the whole chain (BR-53);
 * {@code debt}, {@code overdueDebt}, {@code openDebtCount} and {@code earliestDueDate} cover the store shown, or every
 * store when Owner picks none. {@code earliestDueDate} is in the past when something is overdue.
 */
public record DebtorResponse(long customerId, String customerName, String phone, CreditStatus creditStatus,
                             long totalDebt, long debt, long overdueDebt, int openDebtCount,
                             LocalDate earliestDueDate) {
}
