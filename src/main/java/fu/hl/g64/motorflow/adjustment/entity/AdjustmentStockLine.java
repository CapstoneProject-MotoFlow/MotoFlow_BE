package fu.hl.g64.motorflow.adjustment.entity;

import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Stock change requested by an adjustment. */
@Entity
@Table(name = "adjustment_stock_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjustmentStockLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "adjustment_request_id", nullable = false)
    private Long adjustmentRequestId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** Null: lots are deducted FEFO, then FIFO when the request is applied. */
    @Column(name = "lot_id")
    private Long lotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", nullable = false, length = 30)
    private StockStatus stockStatus;

    @Column(name = "quantity_change", nullable = false)
    private int quantityChange;
}
