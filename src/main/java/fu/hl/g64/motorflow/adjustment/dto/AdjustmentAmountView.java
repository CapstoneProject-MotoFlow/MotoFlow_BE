package fu.hl.g64.motorflow.adjustment.dto;

/**
 * One amount change of a request (UC-84 step 2): a payment's amount or what a receivable still asks for.
 *
 * @param before the amount now, before this request is applied
 * @param after  the amount once applied
 */
public record AdjustmentAmountView(Long paymentId, Long receivableId, String label, long before, long amountChange,
                                   long after) {
}
