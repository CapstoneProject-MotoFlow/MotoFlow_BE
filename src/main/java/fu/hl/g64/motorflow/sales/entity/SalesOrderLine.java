package fu.hl.g64.motorflow.sales.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A part on an order. */
@Entity
@Table(name = "sales_order_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sales_order_id", nullable = false)
    private Long salesOrderId;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "requested_qty", nullable = false)
    private int requestedQty;

    @Column(name = "picked_qty")
    private Integer pickedQty;

    @Column(name = "delivered_qty")
    private Integer deliveredQty;

    @Column(name = "reference_price", nullable = false)
    private long referencePrice;

    @Column(name = "actual_unit_price", nullable = false)
    private long actualUnitPrice;

    /** A part on a new version of the order, priced from the retail price in effect (BR-13). */
    public static SalesOrderLine create(long salesOrderId, int lineNo, long productId, int requestedQty,
                                        long referencePrice, long actualUnitPrice) {
        if (requestedQty <= 0) {
            throw new IllegalArgumentException("requested quantity must be greater than 0");
        }
        SalesOrderLine line = new SalesOrderLine();
        line.salesOrderId = salesOrderId;
        line.lineNo = (short) lineNo;
        line.productId = productId;
        line.requestedQty = requestedQty;
        line.reprice(referencePrice, actualUnitPrice);
        return line;
    }

    /** BR-34: the customer changed the request; what was picked for the old quantity no longer counts. */
    public void changeRequestedQty(int requestedQty) {
        if (requestedQty <= 0) {
            throw new IllegalArgumentException("requested quantity must be greater than 0");
        }
        this.requestedQty = requestedQty;
        pickedQty = null;
        deliveredQty = null;
    }

    /** A new version takes the retail price in effect and the order's discount rate (BR-13, BR-18). */
    public void reprice(long referencePrice, long actualUnitPrice) {
        if (referencePrice <= 0 || actualUnitPrice <= 0) {
            throw new IllegalArgumentException("prices must be greater than 0");
        }
        this.referencePrice = referencePrice;
        this.actualUnitPrice = actualUnitPrice;
    }

    /** UC-57: what the picker actually took; never more than requested. */
    public void recordPicked(int pickedQty) {
        if (pickedQty < 0 || pickedQty > requestedQty) {
            throw new IllegalArgumentException("picked quantity must be 0.." + requestedQty);
        }
        this.pickedQty = pickedQty;
    }

    /** UC-58: what the customer agreed to receive, from the picked quantity (BR-34). */
    public void deliver(int deliveredQty) {
        if (deliveredQty < 0 || pickedQty == null || deliveredQty > pickedQty) {
            throw new IllegalArgumentException("delivered quantity must be 0.." + pickedQty);
        }
        this.deliveredQty = deliveredQty;
    }

    /** The confirmation of the delivered quantities no longer holds for the new version (BR-36). */
    public void clearDelivery() {
        deliveredQty = null;
    }
}
