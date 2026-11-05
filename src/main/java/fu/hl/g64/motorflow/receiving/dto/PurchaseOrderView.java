package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;

import java.util.EnumSet;
import java.util.Set;

/** The tabs of the PO list (screen 6.1.1). Every tab but OPEN is limited to a range of creation days. */
public enum PurchaseOrderView {
    /** Waiting for delivery or receiving: the default tab. */
    OPEN(EnumSet.of(PurchaseOrderStatus.AWAITING_DELIVERY, PurchaseOrderStatus.RECEIVING)),
    FULLY_RECEIVED(EnumSet.of(PurchaseOrderStatus.FULLY_RECEIVED)),
    CANCELLED(EnumSet.of(PurchaseOrderStatus.CANCELLED)),
    ALL(EnumSet.allOf(PurchaseOrderStatus.class));

    private final Set<PurchaseOrderStatus> statuses;

    PurchaseOrderView(Set<PurchaseOrderStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<PurchaseOrderStatus> statuses() {
        return statuses;
    }
}
