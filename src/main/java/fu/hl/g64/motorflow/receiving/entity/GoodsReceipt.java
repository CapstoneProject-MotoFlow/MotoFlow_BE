package fu.hl.g64.motorflow.receiving.entity;

import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptStatus;
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

import java.time.LocalDateTime;

/** One actual delivery inspected at a warehouse: supplier delivery, transfer arrival, or goods returned to the source (BR-22, BR-61). */
@Entity
@Table(name = "goods_receipt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GoodsReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_code", nullable = false, unique = true, length = 30)
    private String receiptCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "receipt_type", nullable = false, length = 20)
    private ReceiptType receiptType;

    @Column(name = "goods_receipt_request_id")
    private Long goodsReceiptRequestId;

    @Column(name = "transfer_order_id")
    private Long transferOrderId;

    @Column(name = "delivery_document_no", length = 50)
    private String deliveryDocumentNo;

    @Column(name = "arrived_at", nullable = false)
    private LocalDateTime arrivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private GoodsReceiptStatus status;

    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;

    @Column(name = "submitted_at", nullable = false)
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
