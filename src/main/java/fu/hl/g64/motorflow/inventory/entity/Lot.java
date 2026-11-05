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

    @Column(name = "goods_receipt_note_line_id", unique = true)
    private Long goodsReceiptNoteLineId;

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

    /**
     * A new lot. A receipt lot points at its goods receipt note line and supplier; an opening or count-surplus lot
     * points at its stock count (CHECKs in the schema).
     */
    public static Lot create(Long productId, LotSourceType sourceType, Long goodsReceiptNoteLineId, Long stockCountId,
                             Long supplierId, Long storeId, LocalDate receivedDate, long purchaseUnitPrice,
                             String manufacturerLotNo, LocalDate manufactureDate, LocalDate expiryDate) {
        boolean receipt = sourceType == LotSourceType.RECEIPT;
        if (receipt != (goodsReceiptNoteLineId != null) || receipt == (stockCountId != null)
                || (!receipt && supplierId != null) || purchaseUnitPrice < 0) {
            throw new IllegalArgumentException("lot source does not match its source type");
        }
        Lot lot = new Lot();
        lot.productId = productId;
        lot.sourceType = sourceType;
        lot.goodsReceiptNoteLineId = goodsReceiptNoteLineId;
        lot.stockCountId = stockCountId;
        lot.supplierId = supplierId;
        lot.storeId = storeId;
        lot.receivedDate = receivedDate;
        lot.purchaseUnitPrice = purchaseUnitPrice;
        lot.manufacturerLotNo = manufacturerLotNo;
        lot.manufactureDate = manufactureDate;
        lot.expiryDate = expiryDate;
        lot.status = LotStatus.IN_STOCK;
        return lot;
    }

    /** True when the lot has an expiry date before {@code today}, or JOB-03 already marked it expired. */
    public boolean isExpiredOn(LocalDate today) {
        return status == LotStatus.EXPIRED || (expiryDate != null && expiryDate.isBefore(today));
    }

    /** Called after every posting: no quantity left anywhere means DEPLETED; stock coming back reopens it. */
    public void refreshDepletion(int quantityLeft) {
        if (quantityLeft == 0 && status == LotStatus.IN_STOCK) {
            status = LotStatus.DEPLETED;
        } else if (quantityLeft > 0 && status == LotStatus.DEPLETED) {
            status = LotStatus.IN_STOCK;
        }
    }
}
