package fu.hl.g64.motorflow.sales.entity.enums;

/**
 * PM 21/11: where the deposit of an order stands. Never stored: it is worked out when the order is read, so an order
 * becomes EXPIRED the day after its expiry date without any job running.
 */
public enum DepositStatus {
    /** No verified deposit. */
    NONE,
    /** The order still waits for stock and holds a deposit within its expiry date. */
    ACTIVE,
    /** The order still waits for stock and today is after the expiry date: it cannot be released or topped up. */
    EXPIRED,
    /** The Branch Manager declared the remaining deposit lost (or the order was cancelled before the expiry date). */
    FORFEITED,
    /** Nothing of the deposit is left on the waiting order: it went into released orders or back to the customer. */
    SETTLED
}
