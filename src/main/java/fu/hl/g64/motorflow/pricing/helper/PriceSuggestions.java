package fu.hl.g64.motorflow.pricing.helper;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.PartCategoryRepository;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.LotRepository.LatestPurchase;
import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.entity.enums.RoundingRule;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * BR-69: suggested retail price = latest purchase price of the part x (1 + markup rate), rounded up by Owner's
 * rounding rule. The latest purchase price is the lot price of the part's most recent supplier receipt in the chain;
 * the average cost is never used. The markup rate is the part's own rate, otherwise the rate of its category or of
 * the nearest parent category that has one. A suggestion never changes the price list by itself (BR-08).
 */
@Component
@RequiredArgsConstructor
public class PriceSuggestions {

    /** Keeps each IN list well below the 32767 bind parameters a PostgreSQL statement can take. */
    public static final int CHUNK = 1000;

    private final PricingPolicyRepository policies;
    private final PartCategoryRepository categories;
    private final LotRepository lots;

    /** The markup configuration in force at {@code now}; no rules if Owner has not set any yet. */
    public MarkupRules rulesInEffect(LocalDateTime now) {
        return MarkupRules.of(policies.findMarkupRulesInEffect(now));
    }

    /** The suggestion basis of each part, also for a part without purchase or rate (its fields are then null). */
    public Map<Long, Suggestion> suggest(Collection<Product> products, MarkupRules rules) {
        Map<Long, Long> parentOf = new HashMap<>();
        categories.findAll().forEach(c -> parentOf.put(c.getId(), c.getParentId()));
        Map<Long, LatestPurchase> purchases = latestPurchases(products.stream().map(Product::getId).toList());

        Map<Long, Suggestion> result = new HashMap<>();
        for (Product product : products) {
            LatestPurchase purchase = purchases.get(product.getId());
            BigDecimal markup = rules.rateFor(product, parentOf);
            Long price = purchase == null ? null : purchase.getPurchaseUnitPrice();
            Long suggested = price == null || markup == null ? null : suggestedPrice(price, markup, rules.rounding());
            result.put(product.getId(), new Suggestion(price, purchase == null ? null : purchase.getReceivedDate(),
                    markup, markup == null ? null : rules.rounding(), suggested));
        }
        return result;
    }

    /** Rounded up to the rule's step; null when the result is not a usable price (a purchase at 0 VND). */
    static Long suggestedPrice(long purchasePrice, BigDecimal markupRate, RoundingRule rounding) {
        BigDecimal raw = BigDecimal.valueOf(purchasePrice).multiply(BigDecimal.valueOf(100).add(markupRate))
                .movePointLeft(2);
        long step = switch (rounding) {
            case NONE -> 1;
            case UP_100 -> 100;
            case UP_500 -> 500;
            case UP_1000 -> 1000;
        };
        long rounded = raw.divide(BigDecimal.valueOf(step), 0, RoundingMode.CEILING).longValueExact() * step;
        return rounded > 0 ? rounded : null;
    }

    private Map<Long, LatestPurchase> latestPurchases(List<Long> productIds) {
        List<LatestPurchase> found = new ArrayList<>();
        for (int from = 0; from < productIds.size(); from += CHUNK) {
            found.addAll(lots.findLatestPurchases(productIds.subList(from, Math.min(from + CHUNK, productIds.size()))));
        }
        return found.stream().collect(Collectors.toMap(LatestPurchase::getProductId, Function.identity()));
    }

    /**
     * One markup configuration: a rate per category and per part, and one rounding rule for every suggestion.
     *
     * @param effectiveFrom null for a configuration that is not saved (a preview) or when Owner has set none
     */
    public record MarkupRules(LocalDateTime effectiveFrom, RoundingRule rounding, Map<Long, BigDecimal> byCategory,
                              Map<Long, BigDecimal> byProduct) {

        public static MarkupRules of(List<PricingPolicy> rows) {
            Map<Long, BigDecimal> byCategory = new HashMap<>();
            Map<Long, BigDecimal> byProduct = new HashMap<>();
            for (PricingPolicy row : rows) {
                if (row.getCategoryId() != null) {
                    byCategory.put(row.getCategoryId(), row.getMarkupRate());
                } else {
                    byProduct.put(row.getProductId(), row.getMarkupRate());
                }
            }
            RoundingRule rounding = rows.stream().map(PricingPolicy::getRoundingRule).filter(r -> r != null)
                    .findFirst().orElse(RoundingRule.NONE);
            LocalDateTime from = rows.isEmpty() ? null : rows.getFirst().getEffectiveFrom();
            return new MarkupRules(from, rounding, byCategory, byProduct);
        }

        /** The part's own rate, else the rate of its category or of the nearest parent that has one. */
        BigDecimal rateFor(Product product, Map<Long, Long> parentOf) {
            BigDecimal own = byProduct.get(product.getId());
            if (own != null) {
                return own;
            }
            Set<Long> seen = new HashSet<>();
            for (Long category = product.getCategoryId(); category != null && seen.add(category);
                 category = parentOf.get(category)) {
                BigDecimal rate = byCategory.get(category);
                if (rate != null) {
                    return rate;
                }
            }
            return null;
        }
    }

    /**
     * What the suggestion of one part is built from. Purchase price and markup rate are cost data for Owner only
     * (BR-72); {@code suggestedPrice} is null when the part has no supplier receipt yet or no markup rate applies.
     */
    public record Suggestion(Long latestPurchasePrice, LocalDate receivedDate, BigDecimal markupRate,
                             RoundingRule roundingRule, Long suggestedPrice) {
    }
}
