package fu.hl.g64.motorflow.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Opening, in, out and closing quantity and value of one part in one warehouse for a period; written once when
 * the period is closed (NXT report, S2d-HKD ledger).
 */
@Entity
@Table(name = "period_stock_balance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PeriodStockBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inventory_period_id", nullable = false)
    private Long inventoryPeriodId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "opening_qty", nullable = false)
    private int openingQty;

    @Column(name = "opening_value", nullable = false)
    private long openingValue;

    @Column(name = "in_qty", nullable = false)
    private int inQty;

    @Column(name = "in_value", nullable = false)
    private long inValue;

    @Column(name = "out_qty", nullable = false)
    private int outQty;

    @Column(name = "out_value", nullable = false)
    private long outValue;

    @Column(name = "closing_qty", nullable = false)
    private int closingQty;

    @Column(name = "closing_value", nullable = false)
    private long closingValue;

    @Column(name = "period_avg_unit_cost", precision = 18, scale = 4)
    private BigDecimal periodAvgUnitCost;
}
