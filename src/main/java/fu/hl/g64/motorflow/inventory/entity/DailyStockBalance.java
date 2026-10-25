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

import java.time.LocalDate;

/** Closing quantity of one part in one warehouse on one day; written by the end-of-day job to look up past stock. */
@Entity
@Table(name = "daily_stock_balance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyStockBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "balance_date", nullable = false)
    private LocalDate balanceDate;

    @Column(name = "closing_qty", nullable = false)
    private int closingQty;
}
