package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;

import java.util.EnumSet;
import java.util.Set;

/** The tabs of the sales order list (screen 8.1.1). Every tab but OPEN is limited to a range of creation days. */
public enum SalesOrderView {
    /** Waiting for picking, picked, or confirmed but not completed, from any day: the default tab. */
    OPEN(EnumSet.of(SalesOrderStatus.AWAITING_PICKING, SalesOrderStatus.PICKED, SalesOrderStatus.CONFIRMED)),
    COMPLETED(EnumSet.of(SalesOrderStatus.COMPLETED)),
    CANCELLED(EnumSet.of(SalesOrderStatus.CANCELLED)),
    ALL(EnumSet.allOf(SalesOrderStatus.class));

    private final Set<SalesOrderStatus> statuses;

    SalesOrderView(Set<SalesOrderStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<SalesOrderStatus> statuses() {
        return statuses;
    }
}
