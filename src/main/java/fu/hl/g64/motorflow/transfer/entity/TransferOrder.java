package fu.hl.g64.motorflow.transfer.entity;

import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
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

/** Goods moved from one source warehouse to one destination warehouse for a replenishment request, or created directly by Owner or the BM (BR-57). */
@Entity
@Table(name = "transfer_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "replenishment_request_id")
    private Long replenishmentRequestId;

    @Column(name = "source_store_id", nullable = false)
    private Long sourceStoreId;

    @Column(name = "dest_store_id", nullable = false)
    private Long destStoreId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferOrderStatus status;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "dispatched_by")
    private Long dispatchedBy;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "extra_dispatch_confirmed_by")
    private Long extraDispatchConfirmedBy;

    @Column(name = "extra_dispatch_confirmed_at")
    private LocalDateTime extraDispatchConfirmedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** UC-71: waits for the source warehouse to prepare and dispatch the goods. */
    public static TransferOrder create(Long replenishmentRequestId, Long sourceStoreId, Long destStoreId, Long createdBy,
                                       LocalDateTime now) {
        TransferOrder order = new TransferOrder();
        order.replenishmentRequestId = replenishmentRequestId;
        order.sourceStoreId = sourceStoreId;
        order.destStoreId = destStoreId;
        order.status = TransferOrderStatus.AWAITING_DISPATCH;
        order.createdBy = createdBy;
        order.createdAt = now;
        return order;
    }

    /** UC-74: the goods left the source and are on the road (BR-58). */
    public void dispatch(Long by, LocalDateTime now) {
        if (status != TransferOrderStatus.AWAITING_DISPATCH) {
            throw new IllegalStateException("transfer order " + id + " is " + status);
        }
        status = TransferOrderStatus.IN_TRANSIT;
        dispatchedBy = by;
        dispatchedAt = now;
    }

    /** UC-75: the source confirmed (part of) the excess received; posted at closing (BR-68). */
    public void confirmExtraDispatch(Long by, LocalDateTime now) {
        extraDispatchConfirmedBy = by;
        extraDispatchConfirmedAt = now;
    }

    /** The first confirmed receipt: what remains is reconciled until the order is closed (UC-76, UC-77). */
    public void startReconciling() {
        if (status == TransferOrderStatus.IN_TRANSIT) {
            status = TransferOrderStatus.RECONCILING;
        }
    }

    /** UC-77: every dispatched unit has a final result. */
    public void close(Long by, LocalDateTime now) {
        if (status != TransferOrderStatus.RECONCILING) {
            throw new IllegalStateException("transfer order " + id + " is " + status);
        }
        status = TransferOrderStatus.CLOSED;
        closedBy = by;
        closedAt = now;
    }
}
