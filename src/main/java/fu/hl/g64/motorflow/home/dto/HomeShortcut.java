package fu.hl.g64.motorflow.home.dto;

/**
 * What waits on the signed-in user, by role (SCR-03 Home). The frontend owns the label and the screen each one opens.
 * Counts of a Branch Manager are those of their own store and its warehouse.
 */
public enum HomeShortcut {

    // Admin
    /** Accounts Admin has locked (UC-10). */
    LOCKED_ACCOUNTS,
    /** Active accounts still on a temporary password (UC-08, UC-11). */
    TEMPORARY_PASSWORD_ACCOUNTS,
    /** Stores still being prepared (UC-04 to UC-06). */
    PLANNED_STORES,

    // Owner: the approval queue (SCR-28) and the decisions only Owner takes
    /** Write-off and correction requests sent for approval. */
    ADJUSTMENTS_AWAITING_APPROVAL,
    /** Supplier deliveries with excess goods waiting for Owner's decision. */
    EXCESS_AWAITING_DECISION,
    /** Stock counts with differences waiting for approval. */
    STOCK_COUNTS_AWAITING_APPROVAL,
    /** Replenishment requests waiting for coordination; also shown to the central-warehouse Branch Manager. */
    REPLENISHMENTS_AWAITING_COORDINATION,

    // Branch Manager
    /** Sales orders waiting to be picked that no Staff member has been given yet. */
    ORDERS_TO_ASSIGN,
    /** Picked sales orders waiting for the Branch Manager's confirmation. */
    ORDERS_AWAITING_CONFIRMATION,
    /** Confirmed sales orders waiting for payment or credit and completion. */
    ORDERS_AWAITING_COMPLETION,
    /** Payments and refunds recorded at the store and not verified yet. */
    PAYMENTS_AWAITING_VERIFICATION,
    /** Arrived goods (supplier deliveries and transfers) waiting for inspection that no Staff member has been given yet. */
    RECEIPTS_TO_ASSIGN,
    /** Inspection results waiting for the Branch Manager's confirmation. */
    RECEIPTS_AWAITING_CONFIRMATION,
    /** Transfer orders the warehouse has to prepare and dispatch. */
    TRANSFERS_AWAITING_DISPATCH,
    /** Open discrepancy cases on supplier deliveries received by the warehouse. */
    OPEN_DISCREPANCIES,

    // Staff: tasks given to them and not started on yet (SCR-31)
    PICKING_TASKS,
    INSPECTION_TASKS,
    DISPATCH_PREPARATION_TASKS,
    COUNTING_TASKS
}
