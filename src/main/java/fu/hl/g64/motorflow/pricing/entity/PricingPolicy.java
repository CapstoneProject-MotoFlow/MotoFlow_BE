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
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Time-effective pricing setting: retail price, markup, order value band, discount threshold, minimum margin or
 * expiry warning (BR-09, BR-10). Rows are never updated except status; the value in force at time t is the ACTIVE
 * row of the same key with the largest effective_from &lt;= t.
 *
 * <p>New versions are written ACTIVE, also when they take effect on a later date, so no job has to switch statuses;
 * whether a version is scheduled, in effect or superseded follows from the effective dates. A key has at most one
 * version still waiting for its date: saving another one for a later day deletes it (its values stay in audit_log),
 * while versions that took effect are kept as history. Markup rates are saved as a whole configuration: the rows
 * sharing the latest effective_from &lt;= t are the rules in force at t (UC-101).
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

    /** UC-35: the chain-wide retail price of one part from {@code effectiveFrom} (BR-09). */
    public static PricingPolicy retailPrice(long productId, long unitPrice, LocalDateTime effectiveFrom, long setBy,
                                            LocalDateTime now) {
        if (unitPrice <= 0) {
            throw new IllegalArgumentException("retail price must be greater than 0");
        }
        PricingPolicy price = version(PolicyType.RETAIL_PRICE, effectiveFrom, setBy, now);
        price.productId = productId;
        price.unitPrice = unitPrice;
        return price;
    }

    /** UC-101: the markup rate of one part category or one part, with the rounding of the suggested price (BR-69). */
    public static PricingPolicy markupRate(Long categoryId, Long productId, BigDecimal markupRate,
                                           RoundingRule roundingRule, LocalDateTime effectiveFrom, long setBy,
                                           LocalDateTime now) {
        if ((categoryId == null) == (productId == null) || markupRate.signum() < 0) {
            throw new IllegalArgumentException("a markup rate belongs to one category or one part and is not negative");
        }
        PricingPolicy rule = version(PolicyType.MARKUP_RATE, effectiveFrom, setBy, now);
        rule.categoryId = categoryId;
        rule.productId = productId;
        rule.markupRate = markupRate.setScale(2, RoundingMode.HALF_UP);
        rule.roundingRule = roundingRule;
        return rule;
    }

    /** UC-38: the maximum invoice-wide discount (BR-11) or the minimum margin (BR-88), in percent. */
    public static PricingPolicy salesLimit(PolicyType type, BigDecimal rate, LocalDateTime effectiveFrom, long setBy,
                                           LocalDateTime now) {
        if ((type != PolicyType.DISCOUNT_THRESHOLD && type != PolicyType.MINIMUM_MARGIN)
                || rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("a sales limit is a discount threshold or a minimum margin of 0-100%");
        }
        PricingPolicy limit = version(type, effectiveFrom, setBy, now);
        limit.rate = rate.setScale(2, RoundingMode.HALF_UP);
        return limit;
    }

    /** UC-104: how many days before expiry a lot counts as near expiry (BR-76). */
    public static PricingPolicy expiryWarning(int days, LocalDateTime effectiveFrom, long setBy, LocalDateTime now) {
        if (days <= 0) {
            throw new IllegalArgumentException("expiry warning days must be greater than 0");
        }
        PricingPolicy warning = version(PolicyType.EXPIRY_WARNING, effectiveFrom, setBy, now);
        warning.expiryWarningDays = days;
        return warning;
    }

    /** True while the version waits for its effective date. */
    public boolean isPending(LocalDateTime now) {
        return effectiveFrom.isAfter(now);
    }

    private static PricingPolicy version(PolicyType type, LocalDateTime effectiveFrom, long setBy, LocalDateTime now) {
        PricingPolicy version = new PricingPolicy();
        version.policyType = type;
        version.effectiveFrom = effectiveFrom;
        version.status = PricingPolicyStatus.ACTIVE;
        version.setBy = setBy;
        version.setAt = now;
        return version;
    }
}
