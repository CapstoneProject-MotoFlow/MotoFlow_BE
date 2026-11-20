package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;

import java.util.EnumSet;
import java.util.Set;

/** The tabs of the sales order list (screen 8.1.1). Every tab but OPEN is limited to a range of creation days. */
public enum SalesOrderView {
    /**
     * Waiting for stock (GVHD 20/11), waiting for picking, picked, or confirmed but not completed, from any day: the
     * default tab.
     */
    OPEN(EnumSet.of(SalesOrderStatus.AWAITING_STOCK, SalesOrderStatus.AWAITING_PICKING, SalesOrderStatus.PICKED,
            SalesOrderStatus.CONFIRMED)),
    /** GVHD 20/11: the "Chờ hàng" tab, waiting orders from any day. */
    WAITING(EnumSet.of(SalesOrderStatus.AWAITING_STOCK)),
    COMPLETED(EnumSet.of(SalesOrderStatus.COMPLETED)),
    CANCELLED(EnumSet.of(SalesOrderStatus.CANCELLED)),
    /** Every sales order; a draft is not one until submitted. */
    ALL(EnumSet.complementOf(EnumSet.of(SalesOrderStatus.DRAFT)));

    private final Set<SalesOrderStatus> statuses;

    SalesOrderView(Set<SalesOrderStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<SalesOrderStatus> statuses() {
        return statuses;
    }
}
