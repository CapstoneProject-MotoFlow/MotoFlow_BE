package fu.hl.g64.motorflow.discrepancy.entity.enums;

/** PM 23/11 (V23): discrepancy_resolution_event.status of a transfer's phiếu nhận hàng giao sai. */
public enum WrongItemReceiptStatus {
    AWAITING_OWNER,
    CONFIRMED,
    /** Only when the Owner finds the receiving store miscounted the line (no rejection). */
    CANCELLED
}
