package fu.hl.g64.motorflow.receiving.entity;

import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
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

/** Inspection result of one part in one delivery. */
@Entity
@Table(name = "goods_receipt_note_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GoodsReceiptNoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goods_receipt_note_id", nullable = false)
    private Long goodsReceiptNoteId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "document_qty", nullable = false)
    private int documentQty;

    @Column(name = "delivered_qty", nullable = false)
    private int deliveredQty;

    @Column(name = "passed_qty", nullable = false)
    private int passedQty;

    @Column(name = "wrong_item_qty", nullable = false)
    private int wrongItemQty;

    @Column(name = "damaged_qty", nullable = false)
    private int damagedQty;

    @Column(name = "excess_qty", nullable = false)
    private int excessQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "excess_decision", nullable = false, length = 20)
    private ExcessDecision excessDecision;

    @Column(name = "unit_price")
    private Long unitPrice;

    @Column(name = "manufacturer_lot_no", length = 50)
    private String manufacturerLotNo;

    @Column(name = "manufacture_date")
    private LocalDate manufactureDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /**
     * UC-106 step 6: one line per part the delivery is expected to bring, at the purchase price Owner agreed on the
     * PO (null for a part that has no PO price). Its quantities come with the inspection result (UC-43).
     */
    public static GoodsReceiptNoteLine expected(long goodsReceiptNoteId, long productId, Long unitPrice) {
        GoodsReceiptNoteLine line = new GoodsReceiptNoteLine();
        line.goodsReceiptNoteId = goodsReceiptNoteId;
        line.productId = productId;
        line.unitPrice = unitPrice;
        line.excessDecision = ExcessDecision.NOT_APPLICABLE;
        return line;
    }

    /** A part of a transfer receipt: {@code documentQty} is what is still expected of the transfer order. */
    public static GoodsReceiptNoteLine transfer(long goodsReceiptNoteId, long productId, int documentQty) {
        GoodsReceiptNoteLine line = new GoodsReceiptNoteLine();
        line.goodsReceiptNoteId = goodsReceiptNoteId;
        line.productId = productId;
        line.documentQty = documentQty;
        line.excessDecision = ExcessDecision.NOT_APPLICABLE;
        return line;
    }

    /**
     * BR-68: transfer excess is not Owner's decision; it waits for the source to confirm an extra dispatch and is
     * posted when the transfer order is closed.
     */
    public void settleTransferExcess(int excess) {
        if (excess < 0 || excess > passedQty) {
            throw new IllegalArgumentException("excess " + excess + " is outside 0.." + passedQty);
        }
        excessQty = excess;
    }

    /**
     * UC-43 step 3 and 6: the recorded quantities and printed lot data; passed = delivered - wrong item - damaged
     * (BR-24). The caller checked that wrong item + damaged does not exceed delivered (MSG27).
     */
    public void record(int documentQty, int deliveredQty, int wrongItemQty, int damagedQty, String manufacturerLotNo,
                       LocalDate manufactureDate, LocalDate expiryDate) {
        if (wrongItemQty + damagedQty > deliveredQty) {
            throw new IllegalArgumentException("wrong item and damaged exceed delivered on line " + id);
        }
        this.documentQty = documentQty;
        this.deliveredQty = deliveredQty;
        this.wrongItemQty = wrongItemQty;
        this.damagedQty = damagedQty;
        this.passedQty = deliveredQty - wrongItemQty - damagedQty;
        this.manufacturerLotNo = manufacturerLotNo;
        this.manufactureDate = manufactureDate;
        this.expiryDate = expiryDate;
    }

    /** BR-24: short quantity = delivery-document quantity - delivered quantity (none when more was delivered). */
    public int shortQty() {
        return Math.max(documentQty - deliveredQty, 0);
    }

    /**
     * UC-44 step 5 and 7: the passed quantity above what the PO or the replacement still expects is excess and waits
     * for Owner (BR-67); the rest goes into available stock.
     */
    public void settleExcess(int excess) {
        if (excess < 0 || excess > passedQty) {
            throw new IllegalArgumentException("excess " + excess + " is outside 0.." + passedQty);
        }
        excessQty = excess;
        excessDecision = excess > 0 ? ExcessDecision.AWAITING_OWNER : ExcessDecision.NOT_APPLICABLE;
    }

    /** What the confirmation put into available stock: passed minus excess. */
    public int acceptedQty() {
        return passedQty - excessQty;
    }

    /** UC-45 step 3: Owner accepts or rejects the whole excess of the line. */
    public void decideExcess(ExcessDecision decision) {
        if (excessDecision != ExcessDecision.AWAITING_OWNER
                || (decision != ExcessDecision.ACCEPTED && decision != ExcessDecision.REJECTED)) {
            throw new IllegalStateException("line " + id + " excess is " + excessDecision + ", cannot become " + decision);
        }
        excessDecision = decision;
    }
}
