package fu.hl.g64.motorflow.sales.helper;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.entity.enums.PolicyType;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sales order pricing (BR-12 to BR-16, BR-88): one discount rate for the whole order on the chain-wide retail price
 * in effect, checked against the discount threshold and the minimum margin that Owner set (UC-38).
 */
@Component
@RequiredArgsConstructor
public class OrderPricing {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PricingPolicyRepository policies;

    /** A quantity of a part at its reference price (BR-14) and actual unit price (BR-13). */
    public record Priced(int quantity, long referencePrice, long actualUnitPrice) {
    }

    /** Totals of an order version for one choice of quantities (requested, or delivered once confirmed). */
    public record Totals(long referenceTotal, long total) {

        public long discountAmount() {
            return referenceTotal - total;
        }

        /** BR-15, before rounding, as a percentage with 4 decimals (for display only; checks use exact arithmetic). */
        public BigDecimal invoiceDiscountRate() {
            if (referenceTotal == 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(Math.max(0, discountAmount())).multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(referenceTotal), 4, RoundingMode.HALF_UP);
        }
    }

    /** The chain-wide retail price in effect of each part that has one (BR-09). */
    public Map<Long, Long> retailPrices(Collection<Long> productIds, LocalDateTime now) {
        Map<Long, Long> prices = new HashMap<>();
        if (productIds.isEmpty()) {
            return prices;
        }
        // Oldest first: the last version of each part wins.
        for (PricingPolicy price : policies.findRetailPricesInEffect(productIds, now)) {
            prices.put(price.getProductId(), price.getUnitPrice());
        }
        return prices;
    }

    /** The maximum discount in percent in effect (BR-11), or null when Owner has never set one. */
    public BigDecimal discountThreshold(LocalDateTime now) {
        return inEffect(PolicyType.DISCOUNT_THRESHOLD, now);
    }

    /** The minimum margin in percent in effect (BR-88); none set counts as 0, so goods are never sold below cost. */
    public BigDecimal minimumMargin(LocalDateTime now) {
        BigDecimal margin = inEffect(PolicyType.MINIMUM_MARGIN, now);
        return margin == null ? BigDecimal.ZERO : margin;
    }

    /** BR-13: reference price × (1 − discount rate), rounded half up to the nearest đồng. */
    public static long actualUnitPrice(long referencePrice, BigDecimal discountRate) {
        return BigDecimal.valueOf(referencePrice).multiply(HUNDRED.subtract(discountRate))
                .divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** BR-14 and BR-37: totals over the given quantities. */
    public static Totals totals(List<Priced> lines) {
        long reference = 0;
        long total = 0;
        for (Priced line : lines) {
            reference = Math.addExact(reference, Math.multiplyExact((long) line.quantity(), line.referencePrice()));
            total = Math.addExact(total, Math.multiplyExact((long) line.quantity(), line.actualUnitPrice()));
        }
        return new Totals(reference, total);
    }

    /**
     * BR-16: the order discount rate and the invoice-wide rate (BR-15, before rounding) must both be below the
     * threshold, for every role. An order without discount always passes. MSG163 when there is a discount but no
     * threshold in effect, MSG162 otherwise.
     */
    public static void checkThreshold(BigDecimal discountRate, Totals totals, BigDecimal threshold) {
        boolean discounted = discountRate.signum() > 0 || totals.discountAmount() > 0;
        if (!discounted) {
            return;
        }
        if (threshold == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG163");
        }
        // (reference − actual) / reference × 100 < threshold, compared without dividing.
        boolean invoiceBelow = BigDecimal.valueOf(Math.max(0, totals.discountAmount())).multiply(HUNDRED)
                .compareTo(threshold.multiply(BigDecimal.valueOf(totals.referenceTotal()))) < 0;
        if (discountRate.compareTo(threshold) >= 0 || !invoiceBelow) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG162", percent(threshold));
        }
    }

    /**
     * BR-88: the lowest order total the delivered goods may be sold for, from the chain-wide moving average cost of
     * each part (BR-74), rounded up to the đồng.
     */
    public static long minimumTotal(BigDecimal cost, BigDecimal minimumMargin) {
        return cost.multiply(HUNDRED.add(minimumMargin)).divide(HUNDRED, 0, RoundingMode.CEILING).longValueExact();
    }

    /** "12,5" for 12.50: how a rate reads in a Vietnamese message. */
    public static String percent(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private BigDecimal inEffect(PolicyType type, LocalDateTime now) {
        // Newest first: the first version that has started is the one in effect.
        return policies.findChainVersions(type).stream().filter(v -> !v.isPending(now)).findFirst()
                .map(PricingPolicy::getRate).orElse(null);
    }
}
