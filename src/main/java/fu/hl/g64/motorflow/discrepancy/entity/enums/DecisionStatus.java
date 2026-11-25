package fu.hl.g64.motorflow.discrepancy.entity.enums;

/**
 * GVHD 24/11 (V26): where the decision of a supplier discrepancy case stands. Only a DECIDED case is acted on (returns,
 * replacement deliveries, goods taken into stock).
 */
public enum DecisionStatus {
    UNDECIDED,
    /** A Branch Manager proposed a direction only the Owner may decide; the proposal is in proposed_*. */
    AWAITING_OWNER,
    DECIDED
}
