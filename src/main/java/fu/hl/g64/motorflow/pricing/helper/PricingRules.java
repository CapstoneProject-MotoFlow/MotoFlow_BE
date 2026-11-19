package fu.hl.g64.motorflow.pricing.helper;

import fu.hl.g64.motorflow.pricing.entity.enums.PolicyType;
import fu.hl.g64.motorflow.pricing.helper.PricingRuleVersions.Version;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The chain-wide pricing rules, cached (R4 III.2.1, "Pricing rules"): markup rates, discount threshold, minimum
 * margin and expiry warning days. The cache holds every ACTIVE version of a rule, current and scheduled, and the
 * version in force is picked by time on each call, so a scheduled version starts at its time without an edit.
 * The versions come from {@link PricingRuleVersions}, a separate bean so that its cache is used (a call to a
 * {@code @Cacheable} method of the same class would skip the cache). Saving a rule clears the cache after the commit.
 * The retail price of each part is not a rule and is not cached.
 */
@Component
@RequiredArgsConstructor
public class PricingRules {

    private final PricingRuleVersions cached;

    /** The rate of a chain-wide limit (discount threshold, minimum margin) in force at {@code now}, or null. */
    public BigDecimal rateInEffect(PolicyType type, LocalDateTime now) {
        return inEffect(type, now).map(Version::rate).orElse(null);
    }

    /** The expiry warning days in force at {@code now}, or null if the Owner has not set them (BR-76). */
    public Integer expiryWarningDaysInEffect(LocalDateTime now) {
        return inEffect(PolicyType.EXPIRY_WARNING, now).map(Version::expiryWarningDays).orElse(null);
    }

    /** The markup rows in force at {@code now}: those of the latest effective date that has started. */
    public List<Version> markupRulesInEffect(LocalDateTime now) {
        List<Version> started = cached.versions(PolicyType.MARKUP_RATE).stream()
                .filter(v -> v.startedBy(now))
                .toList();
        if (started.isEmpty()) {
            return List.of();
        }
        LocalDateTime latest = started.getFirst().effectiveFrom();
        return started.stream().filter(v -> v.effectiveFrom().equals(latest)).toList();
    }

    private Optional<Version> inEffect(PolicyType type, LocalDateTime now) {
        // Newest first: the first version that has started is the one in force.
        return cached.versions(type).stream()
                .filter(v -> v.categoryId() == null && v.productId() == null && v.startedBy(now))
                .findFirst();
    }
}
