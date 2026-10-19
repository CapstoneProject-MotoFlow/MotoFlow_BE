package fu.hl.g64.motorflow.discrepancy.entity.enums;

/** Values of discrepancy_resolution_event.resolution_type (CHECK in the schema). */
public enum ResolutionType {
    MOVED_TO_AWAITING_RETURN,
    RETURNED_TO_SUPPLIER,
    REPLACEMENT_RECEIVED,
    LATE_DELIVERY_RECEIVED,
    RECEIVED_BACK_AT_SOURCE,
    APPROVED_LOSS,
    CLOSED_BY_AGREEMENT
}
