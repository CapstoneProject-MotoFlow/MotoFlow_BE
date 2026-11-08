package fu.hl.g64.motorflow.inventory.entity;

import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
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

/**
 * The Owner's request that a store's Branch Manager count its warehouse (BR-78).
 * The Branch Manager answers it by creating exactly one cycle count, which marks the request DONE.
 */
@Entity
@Table(name = "stock_count_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockCountRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 10)
    private ScopeType scopeType;

    @Column(name = "scope_note")
    private String scopeNote;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private StockCountRequestStatus status;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** UC-105: the Owner asks the store's Branch Manager to count. */
    public static StockCountRequest create(Long storeId, ScopeType scopeType, String scopeNote, String reason,
                                           Long requestedBy, LocalDateTime now) {
        StockCountRequest request = new StockCountRequest();
        request.storeId = storeId;
        request.scopeType = scopeType;
        request.scopeNote = scopeNote;
        request.reason = reason;
        request.status = StockCountRequestStatus.PENDING;
        request.requestedBy = requestedBy;
        request.requestedAt = now;
        return request;
    }

    public boolean isPending() {
        return status == StockCountRequestStatus.PENDING;
    }

    /** UC-91: the Branch Manager created the count for it. */
    public void markDone() {
        requirePending();
        status = StockCountRequestStatus.DONE;
    }

    /** UC-105: the Owner withdrew it before a count was made. */
    public void cancel(LocalDateTime now) {
        requirePending();
        status = StockCountRequestStatus.CANCELLED;
        cancelledAt = now;
    }

    private void requirePending() {
        if (status != StockCountRequestStatus.PENDING) {
            throw new IllegalStateException("stock count request " + id + " is " + status);
        }
    }
}
