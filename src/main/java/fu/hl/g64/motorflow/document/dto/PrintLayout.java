package fu.hl.g64.motorflow.document.dto;

/**
 * PM 21/11 (BE-9): how a sales invoice is laid out. SMALL is the 80 mm thermal receipt of a retail sale, with only the
 * store's name on top; LARGE is the A5 "Phiếu bán hàng" of a wholesale sale, with both parties' tax codes.
 */
public enum PrintLayout {
    SMALL,
    LARGE
}
