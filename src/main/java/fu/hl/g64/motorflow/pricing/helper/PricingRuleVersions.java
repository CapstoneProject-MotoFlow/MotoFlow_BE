package fu.hl.g64.motorflow.pricing.helper;

import fu.hl.g64.motorflow.common.config.CacheConfig;
import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.entity.enums.PolicyType;
import fu.hl.g64.motorflow.pricing.entity.enums.RoundingRule;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** The cached versions of the chain-wide pricing rules, read by {@link PricingRules}. */
@Component
@RequiredArgsConstructor
public class PricingRuleVersions {

    private final PricingPolicyRepository policies;

    /** One version of a rule: a read-only copy of a pricing_policy row, never the JPA entity. */
    public record Version(Long categoryId, Long productId, BigDecimal markupRate, RoundingRule roundingRule,
                          BigDecimal rate, Integer expiryWarningDays, LocalDateTime effectiveFrom) {

        static Version of(PricingPolicy p) {
            return new Version(p.getCategoryId(), p.getProductId(), p.getMarkupRate(), p.getRoundingRule(),
                    p.getRate(), p.getExpiryWarningDays(), p.getEffectiveFrom());
        }

        public boolean startedBy(LocalDateTime now) {
            return !effectiveFrom.isAfter(now);
        }
    }

    /** Every ACTIVE version of a rule type, newest first. Not for RETAIL_PRICE. */
    @Cacheable(cacheNames = CacheConfig.PRICING_RULES, key = "#type.name()")
    @Transactional(readOnly = true)
    public List<Version> versions(PolicyType type) {
        if (type == PolicyType.RETAIL_PRICE) {
            throw new IllegalArgumentException("Retail prices are not cached");
        }
        return policies.findActiveRuleVersions(type).stream().map(Version::of).toList();
    }
}
