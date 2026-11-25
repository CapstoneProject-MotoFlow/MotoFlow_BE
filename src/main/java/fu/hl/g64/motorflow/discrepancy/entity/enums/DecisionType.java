package fu.hl.g64.motorflow.discrepancy.entity.enums;

/** GVHD 24/11 (V26): the direction agreed with the supplier for one supplier discrepancy case. */
public enum DecisionType {
    /** (2) The wrong and damaged goods agreed go back and the supplier replaces them and the short part. */
    RETURN_AND_REPLACE,
    /** (1) The goods agreed go back, nothing is replaced (the Owner waives the replacement). */
    RETURN_NO_REPLACEMENT,
    /** (3) The held goods are taken into stock as they are (by the Owner), nothing goes back or is replaced. */
    ACCEPT_NO_REPLACEMENT
}
