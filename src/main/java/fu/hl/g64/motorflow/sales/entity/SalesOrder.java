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
