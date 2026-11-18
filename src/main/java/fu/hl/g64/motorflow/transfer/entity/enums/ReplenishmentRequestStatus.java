package fu.hl.g64.motorflow.transfer.entity.enums;

/** Values of replenishment_request.status (CHECK in the schema). */
public enum ReplenishmentRequestStatus {
    /** PM 17/11: saved by its creator only; not a request until submitted, so no list, count or coordination sees it. */
    DRAFT,
    AWAITING_COORDINATION,
    IN_PROGRESS,
    CLOSED
}
