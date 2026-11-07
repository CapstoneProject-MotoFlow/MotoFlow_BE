package fu.hl.g64.motorflow.receiving.entity;

import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
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
import java.time.LocalDateTime;

/** One actual delivery inspected at a warehouse: supplier delivery, transfer arrival, or goods returned to the source (BR-22, BR-61). */
@Entity
@Table(name = "goods_receipt_note")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GoodsReceiptNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "receipt_type", nullable = false, length = 20)
    private ReceiptType receiptType;

    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    @Column(name = "transfer_order_id")
    private Long transferOrderId;

    @Column(name = "delivery_document_no", length = 50)
    private String deliveryDocumentNo;

    @Column(name = "delivery_document_date")
    private LocalDate deliveryDocumentDate;

    @Column(name = "deliverer_name", length = 100)
    private String delivererName;

    @Column(name = "vehicle_plate_no", length = 20)
    private String vehiclePlateNo;

    @Column(name = "arrived_at", nullable = false)
    private LocalDateTime arrivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private GoodsReceiptNoteStatus status;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "recount_reason")
    private String recountReason;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "excess_decided_by")
    private Long excessDecidedBy;

    @Column(name = "excess_decided_at")
    private LocalDateTime excessDecidedAt;

    /**
     * UC-106: a supplier delivery that arrived with its delivery document, waiting for a Staff member to inspect it.
     * Stock does not change until the Branch Manager confirms it (UC-44).
     */
    public static GoodsReceiptNote supplierDelivery(long purchaseOrderId, String deliveryDocumentNo,
                                                    LocalDate deliveryDocumentDate, String delivererName,
                                                    String vehiclePlateNo, LocalDateTime arrivedAt) {
        GoodsReceiptNote receipt = new GoodsReceiptNote();
        receipt.receiptType = ReceiptType.SUPPLIER_DELIVERY;
        receipt.purchaseOrderId = purchaseOrderId;
        receipt.deliveryDocumentNo = deliveryDocumentNo;
        receipt.deliveryDocumentDate = deliveryDocumentDate;
        receipt.delivererName = delivererName;
        receipt.vehiclePlateNo = vehiclePlateNo;
        receipt.arrivedAt = arrivedAt;
        receipt.status = GoodsReceiptNoteStatus.AWAITING_INSPECTION;
        return receipt;
    }

    /** Waiting for its inspection result, the first one or a recount (UC-43). */
    public boolean awaitsResult() {
        return status == GoodsReceiptNoteStatus.AWAITING_INSPECTION || status == GoodsReceiptNoteStatus.AWAITING_RECOUNT;
    }

    /**
     * UC-43 step 7: the inspection result waits for the Branch Manager. {@code arrivedAt} corrects the arrival time
     * the Staff member saw (screen 6.2.2), or keeps it when null.
     */
    public void submitResult(long recordedBy, LocalDateTime arrivedAt, LocalDateTime now) {
        require(awaitsResult(), "AWAITING_INSPECTION or AWAITING_RECOUNT");
        if (arrivedAt != null) {
            this.arrivedAt = arrivedAt;
        }
        this.recordedBy = recordedBy;
        submittedAt = now;
        status = GoodsReceiptNoteStatus.AWAITING_CONFIRMATION;
    }

    /** UC-44 Step 3_Return for recount: the same result goes back to be corrected; nothing is posted. */
    public void returnForRecount(String reason) {
        require(status == GoodsReceiptNoteStatus.AWAITING_CONFIRMATION, "AWAITING_CONFIRMATION");
        recountReason = reason;
        status = GoodsReceiptNoteStatus.AWAITING_RECOUNT;
    }

    /** UC-44 step 8. The database refuses a confirmer who recorded the result (BR-26). */
    public void confirm(long confirmedBy, LocalDateTime now) {
        require(status == GoodsReceiptNoteStatus.AWAITING_CONFIRMATION, "AWAITING_CONFIRMATION");
        this.confirmedBy = confirmedBy;
        confirmedAt = now;
        status = GoodsReceiptNoteStatus.CONFIRMED;
    }

    /** UC-45 step 7: Owner decided every excess line of the confirmed receipt. */
    public void recordExcessDecision(long decidedBy, LocalDateTime now) {
        require(status == GoodsReceiptNoteStatus.CONFIRMED, "CONFIRMED");
        excessDecidedBy = decidedBy;
        excessDecidedAt = now;
    }

    private void require(boolean ok, String expected) {
        if (!ok) {
            throw new IllegalStateException("goods receipt note " + id + " is " + status + ", expected " + expected);
        }
    }
}
