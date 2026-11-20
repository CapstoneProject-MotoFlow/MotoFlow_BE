package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * The tabs of the PO list (screen 6.1.1). Every tab but OPEN and AWAITING_RECEIVING_ASSIGNMENT is limited to a range
 * of creation days.
 */
public enum PurchaseOrderView {
    /** Waiting for delivery or receiving: the default tab. */
    OPEN(EnumSet.of(PurchaseOrderStatus.AWAITING_DELIVERY, PurchaseOrderStatus.RECEIVING)),
    /**
     * UC-86 (FE review PR #44 R-03): open POs whose receiving nobody holds, i.e. no SUPPLIER_RECEIVING task is
     * ASSIGNED, for the Branch Manager's "to assign" box. Like OPEN, no default range of creation days.
     */
    AWAITING_RECEIVING_ASSIGNMENT(EnumSet.of(PurchaseOrderStatus.AWAITING_DELIVERY, PurchaseOrderStatus.RECEIVING)),
    FULLY_RECEIVED(EnumSet.of(PurchaseOrderStatus.FULLY_RECEIVED)),
    CANCELLED(EnumSet.of(PurchaseOrderStatus.CANCELLED)),
    /**
     * Test report 20/11 (item 2): POs with a supplier discrepancy case still open, whatever their receiving status.
     * A work queue like OPEN, so no default range of creation days.
     */
    DISCREPANCY_OPEN(EnumSet.complementOf(EnumSet.of(PurchaseOrderStatus.DRAFT))),
    /** Every PO; drafts are never listed here (PM 17/11, N4). */
    ALL(EnumSet.complementOf(EnumSet.of(PurchaseOrderStatus.DRAFT)));

    private final Set<PurchaseOrderStatus> statuses;

    PurchaseOrderView(Set<PurchaseOrderStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<PurchaseOrderStatus> statuses() {
        return statuses;
    }
}
