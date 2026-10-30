package fu.hl.g64.motorflow.inventory.entity;

import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Ledger: one posted change to one stock status of one part in one warehouse, traceable to its source document. */
@Entity
@Table(name = "stock_movement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "lot_id")
    private Long lotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", nullable = false, length = 30)
    private StockStatus stockStatus;

    @Column(name = "quantity_change", nullable = false)
    private int quantityChange;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private MovementType movementType;

    @Column(name = "sales_order_id")
    private Long salesOrderId;

    @Column(name = "goods_receipt_id")
    private Long goodsReceiptId;

    @Column(name = "transfer_order_id")
    private Long transferOrderId;

    @Column(name = "discrepancy_resolution_event_id")
    private Long discrepancyResolutionEventId;

    @Column(name = "adjustment_request_id")
    private Long adjustmentRequestId;

    @Column(name = "stock_count_id")
    private Long stockCountId;

    @Column(name = "inventory_period_id", nullable = false)
    private Long inventoryPeriodId;

    @Column(name = "unit_cost", nullable = false, precision = 18, scale = 4)
    private BigDecimal unitCost;

    @Column(nullable = false)
    private long value;

    @Column
    private String reason;

    @Column(name = "performed_by", nullable = false)
    private Long performedBy;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
}
