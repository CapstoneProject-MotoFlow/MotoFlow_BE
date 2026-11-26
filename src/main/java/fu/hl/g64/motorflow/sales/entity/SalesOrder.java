package fu.hl.g64.motorflow.sales.entity;

import fu.hl.g64.motorflow.sales.entity.enums.CompletionType;
import fu.hl.g64.motorflow.sales.entity.enums.CreditDecision;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/** A sale at one store with its current content; every earlier version is kept in audit_log (BR-36). */
@Entity
@Table(name = "sales_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SalesOrderStatus status;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_name_snapshot", length = 150)
    private String customerNameSnapshot;

    @Column(name = "customer_phone_snapshot", length = 20)
    private String customerPhoneSnapshot;

    @Column(name = "customer_address_snapshot")
    private String customerAddressSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, length = 10)
    private PriceType priceType;

    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_decision", length = 10)
    private CreditDecision creditDecision;

    @Column(name = "approved_credit_amount")
    private Long approvedCreditAmount;

    @Column(name = "credit_decided_by")
    private Long creditDecidedBy;

    @Column(name = "credit_decided_at")
    private LocalDateTime creditDecidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_type", length = 10)
    private CompletionType completionType;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    /** The seller's free-text note on the order; a memo that is not part of a version (BR-36). */
    @Column(length = 500)
    private String note;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** PM 21/11: the last day the deposit of a waiting order holds; null while the order has no deposit. */
    @Column(name = "deposit_expires_on")
    private LocalDate depositExpiresOn;

    /** PM 21/11: when and by whom the customer's remaining deposit was declared lost. */
    @Column(name = "deposit_forfeited_at")
    private LocalDateTime depositForfeitedAt;

    @Column(name = "deposit_forfeited_by")
    private Long depositForfeitedBy;

    @Column(name = "deposit_forfeited_amount")
    private Long depositForfeitedAmount;

    /**
     * GVHD 24/11: the order waited for stock at some point (or is the part a release sold out of a waiting order). It
     * stays true after the order is released or cancelled, so "Đơn chưa đáp ứng đủ" (UC-59) can list it.
     */
    @Column(name = "waited_for_stock", nullable = false)
    private boolean waitedForStock;

    /** GVHD 24/11: the day the store expects the goods of this waiting order; optional. */
    @Column(name = "expected_ready_date")
    private LocalDate expectedReadyDate;

    /** GVHD 24/11: when the store was told this waiting order can be released; cleared when it no longer can. */
    @Column(name = "ready_notified_at")
    private LocalDateTime readyNotifiedAt;

    /** GVHD 20/11: the order this one was split from ("tách từ BH-xxx"); null for an order opened on its own. */
    @Column(name = "split_from_order_id")
    private Long splitFromOrderId;

    /**
     * PM 21/11 (BE-6): the deposit of a waiting order put into this order, which pays for it like a verified payment.
     * An order takes it once: when a partial release opens it, or at the whole release of its own deposit.
     */
    @Column(name = "deposit_applied_amount", nullable = false)
    private long depositAppliedAmount;

    @Column(name = "deposit_applied_at")
    private LocalDateTime depositAppliedAt;

    /**
     * UC-53: a new order of the store, waiting for picking (version 1). {@code customerId} is null for a walk-in sale;
     * the snapshot keeps what the customer was called when the order was taken.
     */
    public static SalesOrder create(long storeId, Customer customer, PriceType priceType, BigDecimal discountRate,
                                    String note, long createdBy, LocalDateTime now) {
        SalesOrder order = new SalesOrder();
        order.storeId = storeId;
        order.status = SalesOrderStatus.AWAITING_PICKING;
        order.setCustomer(customer);
        order.priceType = priceType;
        order.discountRate = discountRate;
        order.note = note;
        order.createdBy = createdBy;
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    /**
     * PM 13/11: a draft its creator keeps on the server. It stays outside the order flow (no stock, picking, money
     * or history) until {@link #submit}.
     */
    public static SalesOrder createDraft(long storeId, Customer customer, PriceType priceType,
                                         BigDecimal discountRate, String note, long createdBy, LocalDateTime now) {
        SalesOrder order = create(storeId, customer, priceType, discountRate, note, createdBy, now);
        order.status = SalesOrderStatus.DRAFT;
        return order;
    }

    /** GVHD 20/11: this new order is split from {@code parentId}; set before it is first saved. */
    public SalesOrder splitFrom(long parentId) {
        if (id != null || splitFromOrderId != null) {
            throw new IllegalStateException("order " + id + " is already saved or split");
        }
        splitFromOrderId = parentId;
        return this;
    }

    /** PM 21/11 (BE-5): a release puts {@code amount} of a waiting order's deposit into this order, once. */
    public void takeDeposit(long amount, LocalDateTime now) {
        if (amount <= 0 || depositAppliedAt != null) {
            throw new IllegalStateException("order " + id + " cannot take a deposit of " + amount);
        }
        depositAppliedAmount = amount;
        depositAppliedAt = now;
        updatedAt = now;
    }

    /** The creator rewrites the whole draft. */
    public void changeDraft(long storeId, Customer customer, PriceType priceType, BigDecimal discountRate,
                            String note, LocalDateTime now) {
        requireDraft();
        this.storeId = storeId;
        setCustomer(customer);
        this.priceType = priceType;
        this.discountRate = discountRate;
        this.note = note;
        updatedAt = now;
    }

    /** The draft becomes a sales order waiting for picking, created now (UC-53). */
    public void submit(LocalDateTime now) {
        requireDraft();
        status = SalesOrderStatus.AWAITING_PICKING;
        createdAt = now;
        updatedAt = now;
    }

    public boolean isDraft() {
        return status == SalesOrderStatus.DRAFT;
    }

    /**
     * GVHD 20/11: the new order holds nothing the store can sell now, so it waits for stock instead of picking. Only
     * right after it was opened, before any picking.
     */
    public void waitForStock() {
        if (status != SalesOrderStatus.AWAITING_PICKING) {
            throw new IllegalStateException("order " + id + " is " + status + ", not just opened");
        }
        status = SalesOrderStatus.AWAITING_STOCK;
        waitedForStock = true;
    }

    /** GVHD 24/11: this new order sells what a partial release took out of a waiting order. */
    public void soldFromWaitingOrder() {
        if (status != SalesOrderStatus.AWAITING_PICKING) {
            throw new IllegalStateException("order " + id + " is " + status + ", not just opened");
        }
        waitedForStock = true;
    }

    /** GVHD 24/11: the store expects the goods on {@code date}, or does not say ({@code null}). */
    public void setExpectedReadyDate(LocalDate date, LocalDateTime now) {
        if (status != SalesOrderStatus.AWAITING_STOCK) {
            throw new IllegalStateException("order " + id + " is " + status + ", not waiting for stock");
        }
        expectedReadyDate = date;
        updatedAt = now;
    }

    /** GVHD 24/11: the store was told the order can be released; it is told again only after {@link #notReady}. */
    public void readyNotified(LocalDateTime now) {
        readyNotifiedAt = now;
    }

    /** GVHD 24/11: the stock that made the order ready went elsewhere. */
    public void notReady() {
        readyNotifiedAt = null;
    }

    /** GVHD 20/11: the whole waiting order is released to picking, keeping its number. */
    public void release(LocalDateTime now) {
        if (status != SalesOrderStatus.AWAITING_STOCK) {
            throw new IllegalStateException("order " + id + " is " + status + ", not waiting for stock");
        }
        status = SalesOrderStatus.AWAITING_PICKING;
        updatedAt = now;
    }

    /** PM 21/11: the deposit of this waiting order holds until {@code date} (set with the first deposit or by BM). */
    public void setDepositExpiry(LocalDate date, LocalDateTime now) {
        if (status != SalesOrderStatus.AWAITING_STOCK) {
            throw new IllegalStateException("order " + id + " is " + status + ", not waiting for stock");
        }
        depositExpiresOn = date;
        updatedAt = now;
    }

    /**
     * PM 21/11 (BE-7): the customer loses the {@code amount} of deposit this waiting order still holds, when it is
     * cancelled or its deposit expired.
     */
    public void forfeitDeposit(long amount, long forfeitedBy, LocalDateTime now) {
        if (status != SalesOrderStatus.AWAITING_STOCK || depositForfeitedAt != null || amount <= 0) {
            throw new IllegalStateException("order " + id + " cannot forfeit a deposit of " + amount);
        }
        depositForfeitedAmount = amount;
        depositForfeitedBy = forfeitedBy;
        depositForfeitedAt = now;
        updatedAt = now;
    }

    public boolean isWaitingForStock() {
        return status == SalesOrderStatus.AWAITING_STOCK;
    }

    /**
     * UC-55: the order moves to a new version with this customer and pricing (BR-36). Its price and credit decision
     * must be confirmed again, so the confirmation and any credit decision are dropped.
     */
    public void revise(Customer customer, PriceType priceType, BigDecimal discountRate, LocalDateTime now) {
        requireUnfinished();
        setCustomer(customer);
        this.priceType = priceType;
        this.discountRate = discountRate;
        dropConfirmation(now);
    }

    /**
     * UC-55: the seller rewrites the note of an unfinished order. The note is a memo, so this alone saves no new
     * version and drops no confirmation. True if the note changed.
     */
    public boolean changeNote(String note, LocalDateTime now) {
        requireUnfinished();
        if (Objects.equals(this.note, note)) {
            return false;
        }
        this.note = note;
        updatedAt = now;
        return true;
    }

    /** The parts or requested quantities changed: the goods must be picked again for the new content (UC-58). */
    public void backToPicking(LocalDateTime now) {
        requireUnfinished();
        if (status == SalesOrderStatus.AWAITING_STOCK) {
            // A waiting order is not picked until it is released (GVHD 20/11).
            updatedAt = now;
            return;
        }
        status = SalesOrderStatus.AWAITING_PICKING;
        dropConfirmation(now);
    }

    /** UC-57: the picker sent the picked quantities; the order waits for the seller's confirmation. */
    public void picked(LocalDateTime now) {
        if (status != SalesOrderStatus.AWAITING_PICKING && status != SalesOrderStatus.PICKED) {
            throw new IllegalStateException("order " + id + " is " + status + ", not waiting for picking");
        }
        status = SalesOrderStatus.PICKED;
        updatedAt = now;
    }

    /** UC-58: delivered quantities and price are agreed for this version; the order waits for payment or credit. */
    public void confirm(long confirmedBy, LocalDateTime now) {
        if (status != SalesOrderStatus.PICKED && status != SalesOrderStatus.CONFIRMED) {
            throw new IllegalStateException("order " + id + " is " + status + ", not picked");
        }
        status = SalesOrderStatus.CONFIRMED;
        this.confirmedBy = confirmedBy;
        confirmedAt = now;
        creditDecision = null;
        approvedCreditAmount = null;
        creditDecidedBy = null;
        creditDecidedAt = null;
        updatedAt = now;
    }

    /**
     * UC-61 step 4: the seller allows the unpaid balance of the confirmed version on credit (BR-41, BR-42). A later
     * change of the order drops the decision with the confirmation (BR-36).
     */
    public void approveCredit(long amount, long decidedBy, LocalDateTime now) {
        requireConfirmed();
        creditDecision = CreditDecision.APPROVED;
        approvedCreditAmount = amount;
        creditDecidedBy = decidedBy;
        creditDecidedAt = now;
        updatedAt = now;
    }

    /**
     * UC-60 step 8, UC-61 step 8: the confirmed version is handed over, paid in full or on the approved credit
     * (BR-38, BR-51). A completed order is never reopened (BR-35).
     */
    public void complete(CompletionType type, long completedBy, LocalDateTime now) {
        requireConfirmed();
        if (type == CompletionType.CREDIT && creditDecision != CreditDecision.APPROVED) {
            throw new IllegalStateException("order " + id + " has no credit approved");
        }
        status = SalesOrderStatus.COMPLETED;
        completionType = type;
        this.completedBy = completedBy;
        completedAt = now;
        updatedAt = now;
    }

    /** UC-56 step 6: the unfinished order is cancelled with its reason; its history stays (BR-39). */
    public void cancel(long cancelledBy, String reason, LocalDateTime now) {
        requireUnfinished();
        status = SalesOrderStatus.CANCELLED;
        this.cancelledBy = cancelledBy;
        cancelledAt = now;
        cancellationReason = reason;
        updatedAt = now;
    }

    /** Neither completed nor cancelled. */
    public boolean isUnfinished() {
        return status != SalesOrderStatus.COMPLETED && status != SalesOrderStatus.CANCELLED;
    }

    /** A confirmed order whose content changes waits for confirmation again; earlier stages stay where they are. */
    private void dropConfirmation(LocalDateTime now) {
        if (status == SalesOrderStatus.CONFIRMED) {
            status = SalesOrderStatus.PICKED;
        }
        confirmedBy = null;
        confirmedAt = null;
        creditDecision = null;
        approvedCreditAmount = null;
        creditDecidedBy = null;
        creditDecidedAt = null;
        updatedAt = now;
    }

    private void setCustomer(Customer customer) {
        customerId = customer.id();
        customerNameSnapshot = customer.name();
        customerPhoneSnapshot = customer.phone();
        customerAddressSnapshot = customer.address();
    }

    private void requireUnfinished() {
        if (!isUnfinished()) {
            throw new IllegalStateException("order " + id + " is " + status);
        }
    }

    private void requireDraft() {
        if (!isDraft()) {
            throw new IllegalStateException("order " + id + " is " + status + ", not a draft");
        }
    }

    private void requireConfirmed() {
        if (status != SalesOrderStatus.CONFIRMED) {
            throw new IllegalStateException("order " + id + " is " + status + ", not confirmed");
        }
    }

    /**
     * Who the order is for: a customer profile ({@code id} set) or a walk-in sale, whose name and phone may be
     * recorded or left empty.
     */
    public record Customer(Long id, String name, String phone, String address) {
    }
}
