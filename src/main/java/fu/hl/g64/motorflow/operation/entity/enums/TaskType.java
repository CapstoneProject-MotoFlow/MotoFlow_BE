package fu.hl.g64.motorflow.operation.entity.enums;

/**
 * Values of warehouse_task.task_type (CHECK in the schema). SUPPLIER_RECEIVING (a purchase order) and
 * TRANSFER_RECEIVING (a transfer order, at its destination): the assignee receives the goods and records the goods
 * receipt note themself, which hands them its INSPECTION.
 */
public enum TaskType {
    PICKING,
    INSPECTION,
    DISPATCH_PREPARATION,
    COUNTING,
    SUPPLIER_RECEIVING,
    TRANSFER_RECEIVING;

    /** Receiving goods and recording their goods receipt note (no result of its own to confirm). */
    public boolean isReceiving() {
        return this == SUPPLIER_RECEIVING || this == TRANSFER_RECEIVING;
    }
}
