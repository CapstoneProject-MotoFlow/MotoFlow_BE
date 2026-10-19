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

/** A part on one order version. */
@Entity
@Table(name = "sales_order_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sales_order_version_id", nullable = false)
    private Long salesOrderVersionId;

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
}
