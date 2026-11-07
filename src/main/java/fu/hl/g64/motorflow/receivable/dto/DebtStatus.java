package fu.hl.g64.motorflow.receivable.dto;

/** Receivable lifecycle of the SRS, derived from what is still owed: Outstanding → Partially paid → Settled. */
public enum DebtStatus {
    OUTSTANDING,
    PARTIALLY_PAID,
    SETTLED
}
