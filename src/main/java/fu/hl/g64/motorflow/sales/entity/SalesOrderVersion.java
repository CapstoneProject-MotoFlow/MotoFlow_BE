package fu.hl.g64.motorflow.sales.entity;

import fu.hl.g64.motorflow.sales.entity.enums.CreditDecision;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
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

/** One version of an order's content. Any change before completion creates a new version (BR-36). */
@Entity
@Table(name = "sales_order_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesOrderVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sales_order_id", nullable = false)
    private Long salesOrderId;

    @Column(name = "version_no", nullable = false)
    private short versionNo;

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

    @Column(name = "suggested_discount_rate", precision = 5, scale = 2)
    private BigDecimal suggestedDiscountRate;

    @Column(name = "change_reason")
    private String changeReason;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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
}
