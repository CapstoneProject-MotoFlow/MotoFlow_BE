package fu.hl.g64.motorflow.inventory.entity;

import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
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

    @Column(name = "store_id", nullable = false)
    private Long storeId;

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
    public static StockBalance withSettingsOf(StockBalance template, Long storeId, LocalDateTime now) {
        StockBalance balance = new StockBalance();
        balance.storeId = storeId;
        balance.productId = template.productId;
        balance.storageArea = template.storageArea;
        balance.binLocation = template.binLocation;
        balance.lowStockThreshold = template.lowStockThreshold;
        balance.updatedAt = now;
        return balance;
    }

    /** Quantity held in one stock status. */
    public int quantityOf(StockStatus status) {
        return switch (status) {
            case AVAILABLE -> availableQty;
            case AWAITING_INSPECTION -> awaitingInspectionQty;
            case WRONG_ITEM -> wrongItemQty;
            case DAMAGED -> damagedQty;
            case AWAITING_SUPPLIER_RETURN -> awaitingSupplierReturnQty;
            case IN_TRANSIT -> inTransitQty;
        };
    }

    /**
     * Adds (or, when negative, removes) stock in one status. Only {@code InventoryPostingService} calls this, in the
     * same transaction as the stock_movement row (BR-29); it has already checked that the result is not negative.
     */
    public void change(StockStatus status, int delta, LocalDateTime now) {
        int result = quantityOf(status) + delta;
        if (result < 0) {
            throw new IllegalStateException("stock_balance would go negative");
        }
        switch (status) {
            case AVAILABLE -> availableQty = result;
            case AWAITING_INSPECTION -> awaitingInspectionQty = result;
            case WRONG_ITEM -> wrongItemQty = result;
            case DAMAGED -> damagedQty = result;
            case AWAITING_SUPPLIER_RETURN -> awaitingSupplierReturnQty = result;
            case IN_TRANSIT -> inTransitQty = result;
        }
        updatedAt = now;
    }

    /** UC-51: where Staff find the part in this warehouse. */
    public void moveTo(String newStorageArea, String newBinLocation, LocalDateTime now) {
        storageArea = newStorageArea;
        binLocation = newBinLocation;
        updatedAt = now;
    }

    /** UC-52: null removes the threshold. */
    public void setLowStockThreshold(Integer threshold, LocalDateTime now) {
        lowStockThreshold = threshold;
        updatedAt = now;
    }

    /** Sellable stock is below the threshold (UC-52, JOB-02). */
    public boolean isLowStock() {
        return lowStockThreshold != null && availableQty < lowStockThreshold;
    }

    /** Stock that carries the moving average (see InventoryPostingService): not wrong, damaged or awaiting return. */
    public int valuedQty() {
        return availableQty + awaitingInspectionQty + inTransitQty;
    }
}
