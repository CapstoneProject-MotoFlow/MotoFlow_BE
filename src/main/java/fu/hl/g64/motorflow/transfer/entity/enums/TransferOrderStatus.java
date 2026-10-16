package fu.hl.g64.motorflow.transfer.entity.enums;

/** Values of transfer_order.status (CHECK in the schema). */
public enum TransferOrderStatus {
    PROPOSED,
    AWAITING_DISPATCH,
    IN_TRANSIT,
    RECONCILING,
    CLOSED,
    CANCELLED
}
