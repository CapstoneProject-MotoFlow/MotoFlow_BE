package fu.hl.g64.motorflow.inventory.entity;

import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
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
 * An opening, year-end or cycle count of one warehouse. Book quantities are fixed when counting starts;
 * a count with no difference completes without approval.
 */
@Entity
@Table(name = "stock_count")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "inventory_period_id")
    private Long inventoryPeriodId;

    /** Set only for a cycle count made from an approved request; Owner-created counts have none. */
    @Column(name = "stock_count_request_id", unique = true)
    private Long stockCountRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "count_type", nullable = false, length = 10)
    private CountType countType;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 10)
    private ScopeType scopeType;

    @Column(name = "scope_note")
    private String scopeNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockCountStatus status;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "counting_started_at")
    private LocalDateTime countingStartedAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
