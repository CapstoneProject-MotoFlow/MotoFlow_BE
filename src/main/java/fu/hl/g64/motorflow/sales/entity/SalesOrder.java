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

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
