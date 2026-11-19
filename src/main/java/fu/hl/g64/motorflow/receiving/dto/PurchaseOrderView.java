package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * The tabs of the PO list (screen 6.1.1). The tabs of work still to do (OPEN, AWAITING_RECEIVING_ASSIGNMENT,
 * AWAITING_DISCREPANCY) list every PO; the others are limited to a range of creation days.
 */
public enum PurchaseOrderView {
    /** Waiting for delivery or receiving: the default tab. */
    OPEN(EnumSet.of(PurchaseOrderStatus.AWAITING_DELIVERY, PurchaseOrderStatus.RECEIVING)),
    /**
     * UC-86 (FE review PR #44 R-03): open POs whose receiving nobody holds, i.e. no SUPPLIER_RECEIVING task is
     * ASSIGNED, for the Branch Manager's "to assign" box. Like OPEN, no default range of creation days.
     */
    AWAITING_RECEIVING_ASSIGNMENT(EnumSet.of(PurchaseOrderStatus.AWAITING_DELIVERY, PurchaseOrderStatus.RECEIVING)),
    /** Received in full with no supplier discrepancy case still open. */
    FULLY_RECEIVED(EnumSet.of(PurchaseOrderStatus.FULLY_RECEIVED)),
    /**
     * Form sync 19/11 (item 12): received in full but a supplier discrepancy case of one of its deliveries is still
     * open (return or replacement owed). Like OPEN, no default range of creation days.
     */
    AWAITING_DISCREPANCY(EnumSet.of(PurchaseOrderStatus.FULLY_RECEIVED)),
    CANCELLED(EnumSet.of(PurchaseOrderStatus.CANCELLED)),
    /** Every PO; drafts are never listed here (PM 17/11, N4). */
    ALL(EnumSet.complementOf(EnumSet.of(PurchaseOrderStatus.DRAFT)));

    private final Set<PurchaseOrderStatus> statuses;

    PurchaseOrderView(Set<PurchaseOrderStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<PurchaseOrderStatus> statuses() {
        return statuses;
    }

    /** True: only POs with an open discrepancy case; false: only those without; null: either. */
    public Boolean openDiscrepancies() {
        return switch (this) {
            case AWAITING_DISCREPANCY -> true;
            case FULLY_RECEIVED -> false;
            default -> null;
        };
    }

    /** Tabs of work still to do keep every PO; the others default to a range of creation days. */
    public boolean unfinished() {
        return this == OPEN || this == AWAITING_RECEIVING_ASSIGNMENT || this == AWAITING_DISCREPANCY;
    }
}
