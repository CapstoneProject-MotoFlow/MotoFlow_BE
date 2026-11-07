package fu.hl.g64.motorflow.sales.dto;

/** The "Cần làm" column of the sales order list (screen 8.1.1): what the order waits for next. */
public enum NextAction {
    /** Nobody is picking it yet: the Branch Manager gives it to someone (UC-86). */
    ASSIGN_PICKER,
    /** The signed-in user holds the picking task and has not sent the result. */
    RECORD_PICKING,
    /** Picked: the seller sets the delivered quantities and confirms (UC-58). */
    CONFIRM,
    /** Confirmed: the order waits for payment or credit (UC-60, UC-61). */
    COLLECT_PAYMENT
}
