package fu.hl.g64.motorflow.pricing.entity;

import fu.hl.g64.motorflow.pricing.entity.enums.PolicyType;
import fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus;
import fu.hl.g64.motorflow.pricing.entity.enums.RoundingRule;
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

/**
 * Time-effective pricing setting: retail price, markup, order value band, discount threshold, minimum margin or
 * expiry warning (BR-09, BR-10). Rows are never updated except status; the value in force at time t is the ACTIVE
 * row of the same key with the largest effective_from &lt;= t.
 */
@Entity
@Table(name = "pricing_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PricingPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_type", nullable = false, length = 25)
    private PolicyType policyType;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "unit_price")
    private Long unitPrice;

    @Column(name = "markup_rate", precision = 5, scale = 2)
    private BigDecimal markupRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_rule", length = 20)
    private RoundingRule roundingRule;

    @Column(name = "order_value_from")
    private Long orderValueFrom;

    @Column(precision = 5, scale = 2)
    private BigDecimal rate;

    @Column(name = "expiry_warning_days")
    private Integer expiryWarningDays;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private PricingPolicyStatus status;

    @Column(name = "set_by", nullable = false)
    private Long setBy;

    @Column(name = "set_at", nullable = false)
    private LocalDateTime setAt;
}
