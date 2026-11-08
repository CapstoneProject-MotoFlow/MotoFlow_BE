package fu.hl.g64.motorflow.receivable.dto;

import fu.hl.g64.motorflow.partner.entity.enums.CreditStatus;

import java.util.List;

/**
 * UC-65 customer debt detail, amounts in VND. {@code totalDebt} is owed to the whole chain (BR-53); {@code storeId} is
 * the store shown, null for every store, and {@code debt} and {@code overdueDebt} cover it. {@code debts} are listed in
 * the order debt payments settle them (BR-70), settled ones included; {@code payments} are the debt payments of those
 * debts, newest first.
 */
public record CustomerDebtsResponse(long customerId, String customerName, String phone, CreditStatus creditStatus,
                                    Long storeId, String storeName, long totalDebt, long debt, long overdueDebt,
                                    List<DebtResponse> debts, List<DebtPaymentResponse> payments) {
}
