package fu.hl.g64.motorflow.inventory.entity;

import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.LotStatus;
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

import java.time.LocalDate;

/**
 * A lot of one part from a supplier receipt line, the opening count or a count surplus. Lots are never merged;
 * they drive FEFO/FIFO picking and traceability, not the cost.
 */
@Entity
@Table(name = "lot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 15)
    private LotSourceType sourceType;

    @Column(name = "goods_receipt_line_id", unique = true)
    private Long goodsReceiptLineId;

    @Column(name = "stock_count_id")
    private Long stockCountId;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    @Column(name = "purchase_unit_price", nullable = false)
    private long purchaseUnitPrice;

    @Column(name = "manufacturer_lot_no", length = 50)
    private String manufacturerLotNo;

    @Column(name = "manufacture_date")
    private LocalDate manufactureDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LotStatus status;
}
