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
import java.math.RoundingMode;
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

    @Column(name = "goods_receipt_note_id")
    private Long goodsReceiptNoteId;

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

    /** One ledger row. Exactly one source document id is set (CHECK in the schema). */
    public static StockMovement record(Long storeId, Long productId, Long lotId, Long inventoryPeriodId,
                                       StockStatus stockStatus, int quantityChange, BigDecimal unitCost,
                                       MovementType movementType, SourceRef source, String reason,
                                       Long performedBy, LocalDateTime occurredAt) {
        if (quantityChange == 0) {
            throw new IllegalArgumentException("quantity_change must not be zero");
        }
        StockMovement m = new StockMovement();
        m.storeId = storeId;
        m.productId = productId;
        m.lotId = lotId;
        m.inventoryPeriodId = inventoryPeriodId;
        m.stockStatus = stockStatus;
        m.quantityChange = quantityChange;
        m.unitCost = unitCost;
        m.value = unitCost.multiply(BigDecimal.valueOf(quantityChange)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        m.movementType = movementType;
        switch (source.type()) {
            case SALES_ORDER -> m.salesOrderId = source.id();
            case GOODS_RECEIPT_NOTE -> m.goodsReceiptNoteId = source.id();
            case TRANSFER_ORDER -> m.transferOrderId = source.id();
            case DISCREPANCY_RESOLUTION_EVENT -> m.discrepancyResolutionEventId = source.id();
            case ADJUSTMENT_REQUEST -> m.adjustmentRequestId = source.id();
            case STOCK_COUNT -> m.stockCountId = source.id();
        }
        m.reason = reason;
        m.performedBy = performedBy;
        m.occurredAt = occurredAt;
        return m;
    }

    /** The document a movement comes from; maps to exactly one of the source columns. */
    public record SourceRef(SourceType type, long id) {
        public enum SourceType {
            SALES_ORDER, GOODS_RECEIPT_NOTE, TRANSFER_ORDER, DISCREPANCY_RESOLUTION_EVENT, ADJUSTMENT_REQUEST, STOCK_COUNT
        }

        public static SourceRef salesOrder(long id) { return new SourceRef(SourceType.SALES_ORDER, id); }
        public static SourceRef goodsReceiptNote(long id) { return new SourceRef(SourceType.GOODS_RECEIPT_NOTE, id); }
        public static SourceRef transferOrder(long id) { return new SourceRef(SourceType.TRANSFER_ORDER, id); }
        public static SourceRef discrepancyResolutionEvent(long id) { return new SourceRef(SourceType.DISCREPANCY_RESOLUTION_EVENT, id); }
        public static SourceRef adjustmentRequest(long id) { return new SourceRef(SourceType.ADJUSTMENT_REQUEST, id); }
        public static SourceRef stockCount(long id) { return new SourceRef(SourceType.STOCK_COUNT, id); }
    }
}
