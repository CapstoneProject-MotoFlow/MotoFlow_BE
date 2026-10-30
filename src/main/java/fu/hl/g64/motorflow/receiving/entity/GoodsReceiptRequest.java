package fu.hl.g64.motorflow.receiving.entity;

import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptRequestStatus;
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

/** A purchase agreed between Owner and a supplier outside MotoFlow, sent to the receiving warehouse (BR-19, BR-20). */
@Entity
@Table(name = "goods_receipt_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GoodsReceiptRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "replenishment_request_id")
    private Long replenishmentRequestId;

    @Column(name = "expected_arrival")
    private LocalDate expectedArrival;

    @Column(length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GoodsReceiptRequestStatus status;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;
}
