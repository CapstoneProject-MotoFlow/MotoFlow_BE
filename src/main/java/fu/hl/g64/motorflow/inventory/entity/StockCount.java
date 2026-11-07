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

import java.time.LocalDate;
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

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "inventory_period_id")
    private Long inventoryPeriodId;

    /** Set only for a cycle count made from an approved request; Owner-created counts have none. */
    @Column(name = "stock_count_request_id", unique = true)
    private Long stockCountRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "count_type", nullable = false, length = 10)
    private CountType countType;

    @Column(name = "count_date", nullable = false)
    private LocalDate countDate;

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

    /** UC-91: a year-end or cycle count waiting for its counter; {@code inventoryPeriodId} is the period of the count date. */
    public static StockCount create(Long storeId, Long inventoryPeriodId, Long stockCountRequestId, CountType countType,
                                    LocalDate countDate, ScopeType scopeType, String scopeNote, Long createdBy,
                                    LocalDateTime now) {
        StockCount count = new StockCount();
        count.storeId = storeId;
        count.inventoryPeriodId = inventoryPeriodId;
        count.stockCountRequestId = stockCountRequestId;
        count.countType = countType;
        count.countDate = countDate;
        count.scopeType = scopeType;
        count.scopeNote = scopeNote;
        count.status = StockCountStatus.DRAFT;
        count.createdBy = createdBy;
        count.createdAt = now;
        return count;
    }

    /** Draft, counting or waiting for the Owner: the warehouse cannot start another count (SC-1). */
    public boolean isUnfinished() {
        return status == StockCountStatus.DRAFT || status == StockCountStatus.COUNTING
                || status == StockCountStatus.AWAITING_APPROVAL;
    }

    /**
     * Book quantities still have to be taken: the count was never started, or lines came back for a recount
     * (UC-93, BR-79). {@link #startCounting} clears it.
     */
    public boolean needsBookQuantities() {
        return countingStartedAt == null;
    }

    /** UC-93: book quantities were just taken; the counter records from now on. */
    public void startCounting(LocalDateTime now) {
        requireStatus(StockCountStatus.DRAFT, StockCountStatus.COUNTING);
        status = StockCountStatus.COUNTING;
        countingStartedAt = now;
    }

    /**
     * UC-94: lines go back to the counter, from COUNTING (Branch Manager) or AWAITING_APPROVAL (Owner). The count waits
     * for new book quantities of those lines; {@code confirmedAt} is kept, so lines can no longer be removed (SC-14).
     */
    public void returnForRecount() {
        requireStatus(StockCountStatus.COUNTING, StockCountStatus.AWAITING_APPROVAL);
        status = StockCountStatus.COUNTING;
        countingStartedAt = null;
    }

    /** UC-94: the Branch Manager confirmed the result; with a difference the Owner approves it, otherwise it is done. */
    public void confirm(Long confirmedBy, boolean hasDifference, LocalDateTime now) {
        requireStatus(StockCountStatus.COUNTING);
        this.confirmedBy = confirmedBy;
        confirmedAt = now;
        status = hasDifference ? StockCountStatus.AWAITING_APPROVAL : StockCountStatus.COMPLETED;
    }

    /** Sent to the Owner at least once; an Owner recount keeps it so (SC-14). */
    public boolean wasConfirmed() {
        return confirmedAt != null;
    }

    /** UC-95: the Owner approved the differences, which are posted in the same transaction. */
    public void approve(Long approvedBy, String decisionReason, LocalDateTime now) {
        requireStatus(StockCountStatus.AWAITING_APPROVAL);
        this.approvedBy = approvedBy;
        this.decisionReason = decisionReason;
        approvedAt = now;
        status = StockCountStatus.COMPLETED;
    }

    /** UC-94: the Branch Manager cancelled the count before it went to the Owner. */
    public void cancel(Long cancelledBy, String reason, LocalDateTime now) {
        requireStatus(StockCountStatus.DRAFT, StockCountStatus.COUNTING);
        this.cancelledBy = cancelledBy;
        cancellationReason = reason;
        cancelledAt = now;
        status = StockCountStatus.CANCELLED;
    }

    private void requireStatus(StockCountStatus... allowed) {
        for (StockCountStatus s : allowed) {
            if (status == s) {
                return;
            }
        }
        throw new IllegalStateException("stock count " + id + " is " + status);
    }
}
