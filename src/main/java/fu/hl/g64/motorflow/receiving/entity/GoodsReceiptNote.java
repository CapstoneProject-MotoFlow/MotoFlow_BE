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
}
