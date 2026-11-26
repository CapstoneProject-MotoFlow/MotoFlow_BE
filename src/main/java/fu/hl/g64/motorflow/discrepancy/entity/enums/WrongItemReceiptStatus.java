package fu.hl.g64.motorflow.discrepancy.entity.enums;

/**
 * PM 23/11 (V23): discrepancy_resolution_event.status of a transfer's phiếu nhận hàng giao sai. GVHD 24/11 (V27): it
 * waits for the source store's BM, who confirms or rejects it.
 */
public enum WrongItemReceiptStatus {
    AWAITING_SOURCE,
    CONFIRMED,
    /** GVHD 24/11: the source's BM found the phiếu wrong; the wrong items are undecided again. */
    REJECTED,
    /** Only when the Owner finds the receiving store miscounted the line. */
    CANCELLED
}
