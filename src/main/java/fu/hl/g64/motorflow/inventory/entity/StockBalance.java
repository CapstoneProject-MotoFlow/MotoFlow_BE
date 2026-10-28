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

import java.time.LocalDateTime;

/** Current quantity of one part in one warehouse by stock status (BR-29), with its storage location and low-stock threshold. */
@Entity
@Table(name = "stock_balance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "awaiting_inspection_qty", nullable = false)
    private int awaitingInspectionQty;

    @Column(name = "wrong_item_qty", nullable = false)
    private int wrongItemQty;

    @Column(name = "damaged_qty", nullable = false)
    private int damagedQty;

    @Column(name = "awaiting_supplier_return_qty", nullable = false)
    private int awaitingSupplierReturnQty;

    @Column(name = "in_transit_qty", nullable = false)
    private int inTransitQty;

    @Column(name = "storage_area", length = 50)
    private String storageArea;

    @Column(name = "bin_location", length = 50)
    private String binLocation;

    @Column(name = "low_stock_threshold")
    private Integer lowStockThreshold;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** UC-05: the template's storage area, bin location and low-stock threshold in another warehouse, with no stock. */
    public static StockBalance withSettingsOf(StockBalance template, Long warehouseId, LocalDateTime now) {
        StockBalance balance = new StockBalance();
        balance.warehouseId = warehouseId;
        balance.productId = template.productId;
        balance.storageArea = template.storageArea;
        balance.binLocation = template.binLocation;
        balance.lowStockThreshold = template.lowStockThreshold;
        balance.updatedAt = now;
        return balance;
    }
}
