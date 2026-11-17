package fu.hl.g64.motorflow.receiving.entity;

import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
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
@Table(name = "purchase_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder {

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
    private PurchaseOrderStatus status;

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

    /** UC-40: the PO waits for its first delivery. Stock does not change and nothing is sent to the supplier. */
    public static PurchaseOrder create(long supplierId, long storeId, LocalDate expectedArrival, String note,
                                       long createdBy, LocalDateTime now) {
        PurchaseOrder order = new PurchaseOrder();
        order.supplierId = supplierId;
        order.storeId = storeId;
        order.expectedArrival = expectedArrival;
        order.note = note;
        order.status = PurchaseOrderStatus.AWAITING_DELIVERY;
        order.createdBy = createdBy;
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    /**
     * PM 17/11: a draft saved by its creator. It holds no receiving task, sends no notification and is seen by its
     * creator only; {@code createdAt} is when it was last saved until it is submitted.
     */
    public static PurchaseOrder draft(long supplierId, long storeId, LocalDate expectedArrival, String note,
                                      long createdBy, LocalDateTime now) {
        PurchaseOrder order = create(supplierId, storeId, expectedArrival, note, createdBy, now);
        order.status = PurchaseOrderStatus.DRAFT;
        return order;
    }

    public boolean isDraft() {
        return status == PurchaseOrderStatus.DRAFT;
    }

    /** The whole content of a draft replaced; the lines change through {@link PurchaseOrderLine}. */
    public void replaceDraft(long supplierId, long storeId, LocalDate expectedArrival, String note,
                             LocalDateTime now) {
        requireDraft();
        this.supplierId = supplierId;
        this.storeId = storeId;
        this.expectedArrival = expectedArrival;
        this.note = note;
        createdAt = now;
        updatedAt = now;
    }

    /** "Tạo đơn" on a draft: it becomes a PO waiting for delivery with the same id, created now. */
    public void submit(LocalDateTime now) {
        requireDraft();
        status = PurchaseOrderStatus.AWAITING_DELIVERY;
        createdAt = now;
        updatedAt = now;
    }

    private void requireDraft() {
        if (status != PurchaseOrderStatus.DRAFT) {
            throw new IllegalStateException("purchase order " + id + " is " + status + ", expected DRAFT");
        }
    }

    /** BR-55: the PO buys the shortage of this replenishment request. */
    public void linkReplenishment(Long replenishmentRequestId) {
        this.replenishmentRequestId = replenishmentRequestId;
    }

    /** Still expecting goods: waiting for a delivery or receiving one. */
    public boolean isOpen() {
        return status == PurchaseOrderStatus.AWAITING_DELIVERY || status == PurchaseOrderStatus.RECEIVING;
    }

    /**
     * UC-42: new delivery details. The caller has checked BR-21 (no GRN yet) under the row lock; the lines change
     * through {@link PurchaseOrderLine}.
     */
    public void changeDelivery(LocalDate expectedArrival, String note, LocalDateTime now) {
        requireAwaitingDelivery();
        this.expectedArrival = expectedArrival;
        this.note = note;
        updatedAt = now;
    }

    /** UC-42 Cancel PO, while no GRN has been recorded for it (BR-21, checked by the caller). */
    public void cancel(long by, String reason, LocalDateTime now) {
        requireAwaitingDelivery();
        status = PurchaseOrderStatus.CANCELLED;
        cancelledBy = by;
        cancelledAt = now;
        cancellationReason = reason;
        updatedAt = now;
    }

    /** UC-106: its first delivery is recorded, so the PO can no longer change or be cancelled (BR-21). */
    public void startReceiving(LocalDateTime now) {
        if (status == PurchaseOrderStatus.AWAITING_DELIVERY) {
            status = PurchaseOrderStatus.RECEIVING;
            updatedAt = now;
        }
    }

    /** UC-44 step 8: every line has been delivered in full (BR-22); later deliveries can only be replacements. */
    public void completeReceiving(LocalDateTime now) {
        if (status != PurchaseOrderStatus.RECEIVING) {
            throw new IllegalStateException("purchase order " + id + " is " + status + ", expected RECEIVING");
        }
        status = PurchaseOrderStatus.FULLY_RECEIVED;
        updatedAt = now;
    }

    private void requireAwaitingDelivery() {
        if (status != PurchaseOrderStatus.AWAITING_DELIVERY) {
            throw new IllegalStateException("purchase order " + id + " is " + status + ", expected AWAITING_DELIVERY");
        }
    }
}
