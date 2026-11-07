package fu.hl.g64.motorflow.sales.dto;

/**
 * The order after money was recorded or verified (UC-60 steps 6-9). When the order became fully paid the system tried
 * to complete it: {@code completed} says whether it did, and if completion was blocked, {@code completionBlockedCode}
 * and {@code completionBlockedMessage} say why (UC-60 Step 7_Completion conditions fail). The money stays recorded
 * either way. {@code paymentId} is the payment or refund the request recorded or verified.
 */
public record SettlementResult(SalesOrderDetail order, long paymentId, boolean completed,
                               String completionBlockedCode, String completionBlockedMessage) {
}
