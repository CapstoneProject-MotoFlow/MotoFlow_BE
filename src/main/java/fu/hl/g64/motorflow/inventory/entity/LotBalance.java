package fu.hl.g64.motorflow.inventory.entity;

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

import java.time.LocalDateTime;

/** Quantity of one lot in one warehouse by stock status. Updated with stock_movement; the sum over lots equals stock_balance. */
@Entity
@Table(name = "lot_balance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LotBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", nullable = false, length = 30)
    private StockStatus stockStatus;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
