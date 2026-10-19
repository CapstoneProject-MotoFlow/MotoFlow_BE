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

/** Goods moved from one source warehouse to one destination warehouse for one replenishment request (BR-57). */
@Entity
@Table(name = "transfer_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_code", nullable = false, unique = true, length = 30)
    private String transferCode;

    @Column(name = "replenishment_request_id", nullable = false)
    private Long replenishmentRequestId;

    @Column(name = "source_warehouse_id", nullable = false)
    private Long sourceWarehouseId;

    @Column(name = "dest_warehouse_id", nullable = false)
    private Long destWarehouseId;

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

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;
}
