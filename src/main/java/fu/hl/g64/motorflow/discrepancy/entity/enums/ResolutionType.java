package fu.hl.g64.motorflow.discrepancy.entity.enums;

/** Values of discrepancy_resolution_event.resolution_type (CHECK in the schema). */
public enum ResolutionType {
    MOVED_TO_AWAITING_RETURN,
    /** Test report 20/11: the store keeps goods it had agreed to return, so they leave awaiting return (V14). */
    RELEASED_FROM_AWAITING_RETURN,
    RETURNED_TO_SUPPLIER,
    REPLACEMENT_RECEIVED,
    LATE_DELIVERY_RECEIVED,
    RECEIVED_BACK_AT_SOURCE,
    APPROVED_LOSS,
    CLOSED_BY_AGREEMENT,
    /** GVHD 20/11 (PR 5, V15): Owner took dented goods into stock as the part's "-M" variant. */
    ACCEPTED_AS_VARIANT,
    /** GVHD 20/11 (PR 5, V15): Owner took goods delivered by mistake into stock as the part they really are. */
    RECEIVED_AS_OTHER_PRODUCT
}
