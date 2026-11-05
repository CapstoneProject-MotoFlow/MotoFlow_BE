package fu.hl.g64.motorflow.pricing.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.PartCategory;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.PartCategoryRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.pricing.dto.MarkupPreviewRequest;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesRequest;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesRequest.CategoryMarkup;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesRequest.ProductMarkup;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesResponse;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesResponse.CategoryRate;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesResponse.MarkupConfiguration;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesResponse.ProductRate;
import fu.hl.g64.motorflow.pricing.dto.SalesLimitRequest;
import fu.hl.g64.motorflow.pricing.dto.SalesLimitsResponse;
import fu.hl.g64.motorflow.pricing.dto.SalesLimitsResponse.RateVersion;
import fu.hl.g64.motorflow.pricing.dto.SalesLimitsResponse.SalesLimit;
import fu.hl.g64.motorflow.pricing.dto.SuggestionPreview;
import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.entity.enums.PolicyType;
import fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus;
import fu.hl.g64.motorflow.pricing.entity.enums.RoundingRule;
import fu.hl.g64.motorflow.pricing.helper.PriceSuggestions;
import fu.hl.g64.motorflow.pricing.helper.PriceSuggestions.MarkupRules;
import fu.hl.g64.motorflow.pricing.helper.PriceSuggestions.Suggestion;
import fu.hl.g64.motorflow.pricing.helper.PricingVersions;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import fu.hl.g64.motorflow.pricing.service.PricingRuleService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PricingRuleServiceImpl implements PricingRuleService {

    private static final String MARKUP_RULES_SET = "MARKUP_RULES_SET";
    /** How many recently received parts a preview shows when Owner picks none. */
    private static final int SAMPLE_PARTS = 10;

    private final PricingPolicyRepository pricingPolicies;
    private final PartCategoryRepository categories;
    private final ProductRepository products;
    private final LotRepository lots;
    private final PriceSuggestions suggestions;
    private final AuditLogRepository auditLog;
    private final UserAccountRepository users;
    private final Clock clock;

    // UC-38
    @Transactional(readOnly = true)
    @Override
    public SalesLimitsResponse salesLimits() {
        LocalDateTime now = LocalDateTime.now(clock);
        return new SalesLimitsResponse(limit(PolicyType.DISCOUNT_THRESHOLD, now), limit(PolicyType.MINIMUM_MARGIN, now));
    }

    // UC-38
    @Transactional
    @Override
    public SalesLimitsResponse setSalesLimit(AuthenticatedUser owner, SalesLimitRequest request) {
        PolicyType type = request.limitType().policyType();
        pricingPolicies.lockSetting(type.name());
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = PricingVersions.effectiveFrom(request.effectiveDate(), now);
        List<PricingPolicy> replaced = PricingVersions.replacedBy(from, pricingPolicies.findChainVersions(type), now);
        if (!replaced.isEmpty()) {
            // Deleted at once, before the insert, because the new version may take the same date.
            pricingPolicies.deleteAllInBatch(replaced);
        }
        PricingPolicy saved = pricingPolicies.save(PricingPolicy.salesLimit(type, request.rate(), from, owner.id(), now));

        Map<String, Object> oldData = replaced.isEmpty() ? null : Map.of("replaced_scheduled", replaced.stream()
                .map(v -> Map.<String, Object>of("rate", v.getRate(), "effective_from", v.getEffectiveFrom().toString()))
                .toList());
        auditLog.save(AuditLog.record(RetailPriceServiceImpl.AUDIT_ENTITY, saved.getId(), type.name() + "_SET",
                owner.id(), null, oldData, Map.of("rate", saved.getRate(), "effective_from", from.toString()), now));
        return salesLimits();
    }

    // UC-101
    @Transactional(readOnly = true)
    @Override
    public MarkupRulesResponse markupRules() {
        LocalDateTime now = LocalDateTime.now(clock);
        return new MarkupRulesResponse(configuration(pricingPolicies.findMarkupRulesInEffect(now)),
                configuration(nearest(pricingPolicies.findScheduledMarkupRules(now))));
    }

    // UC-101
    @Transactional
    @Override
    public MarkupRulesResponse setMarkupRules(AuthenticatedUser owner, MarkupRulesRequest request) {
        if (request.categories().isEmpty() && request.products().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG128");
        }
        requireKnown(rulesOf(request.roundingRule(), request.categories(), request.products()));
        pricingPolicies.lockSetting(PolicyType.MARKUP_RATE.name());
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = PricingVersions.effectiveFrom(request.effectiveDate(), now);
        List<PricingPolicy> replaced = PricingVersions.replacedBy(from, pricingPolicies.findScheduledMarkupRules(now),
                now);
        if (!replaced.isEmpty()) {
            // Deleted at once, before the insert, because the new configuration may take the same date.
            pricingPolicies.deleteAllInBatch(replaced);
        }

        List<PricingPolicy> rows = new ArrayList<>();
        request.categories().forEach(c -> rows.add(PricingPolicy.markupRate(c.categoryId(), null, c.markupRate(),
                request.roundingRule(), from, owner.id(), now)));
        request.products().forEach(p -> rows.add(PricingPolicy.markupRate(null, p.productId(), p.markupRate(),
                request.roundingRule(), from, owner.id(), now)));
        List<PricingPolicy> saved = pricingPolicies.saveAll(rows);
        auditLog.save(AuditLog.record(RetailPriceServiceImpl.AUDIT_ENTITY, saved.getFirst().getId(), MARKUP_RULES_SET,
                owner.id(), null, replaced.isEmpty() ? null : Map.of("replaced_scheduled", values(replaced)),
                values(saved), now));
        return markupRules();
    }

    // UC-101
    @Transactional(readOnly = true)
    @Override
    public List<SuggestionPreview> preview(MarkupPreviewRequest request) {
        MarkupRules rules = rulesOf(request.roundingRule(), request.categories(), request.products());
        List<Long> ids = request.productIds().isEmpty() ? lots.findRecentlyPurchasedProductIds(SAMPLE_PARTS)
                : request.productIds().stream().distinct().toList();
        List<Product> parts = products.findAllById(ids);
        if (parts.size() != ids.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG57");
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        Map<Long, Suggestion> suggested = suggestions.suggest(parts, rules);
        Map<Long, Long> current = pricingPolicies.findRetailPricesInEffect(ids, LocalDateTime.now(clock)).stream()
                .collect(Collectors.toMap(PricingPolicy::getProductId, PricingPolicy::getUnitPrice, (older, newer) -> newer));
        return parts.stream().sorted(Comparator.comparing(Product::getSku)).map(p -> {
            Suggestion s = suggested.get(p.getId());
            return new SuggestionPreview(p.getId(), p.getSku(), p.getPartName(), current.get(p.getId()),
                    s.latestPurchasePrice(), s.receivedDate(), s.markupRate(), s.suggestedPrice());
        }).toList();
    }

    private SalesLimit limit(PolicyType type, LocalDateTime now) {
        List<PricingPolicy> versions = pricingPolicies.findChainVersions(type);
        Map<Long, String> names = userNames(versions);
        List<RateVersion> history = PricingVersions.timeline(versions, now, (v, effectiveTo, state) ->
                new RateVersion(v.getId(), v.getRate(), v.getEffectiveFrom(), effectiveTo, state, v.getSetBy(),
                        names.get(v.getSetBy()), v.getSetAt()));
        RateVersion current = history.stream().filter(v -> v.state() == PricingPolicyStatus.ACTIVE).findFirst()
                .orElse(null);
        // Newest first, so the last version still waiting is the next one to apply.
        RateVersion scheduled = history.stream().filter(v -> v.state() == PricingPolicyStatus.SCHEDULED)
                .reduce((newer, older) -> older).orElse(null);
        return new SalesLimit(current, scheduled, history);
    }

    /** One configuration per date: the rows of the earliest date waiting to apply. */
    private static List<PricingPolicy> nearest(List<PricingPolicy> pending) {
        return pending.stream().map(PricingPolicy::getEffectiveFrom).min(Comparator.naturalOrder())
                .map(first -> pending.stream().filter(p -> p.getEffectiveFrom().equals(first)).toList())
                .orElse(List.of());
    }

    private MarkupConfiguration configuration(List<PricingPolicy> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        Map<Long, PartCategory> categoryById = categories.findAllById(rows.stream().map(PricingPolicy::getCategoryId)
                        .filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(PartCategory::getId, Function.identity()));
        Map<Long, Product> productById = products.findAllById(rows.stream().map(PricingPolicy::getProductId)
                        .filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<CategoryRate> categoryRates = rows.stream().filter(r -> r.getCategoryId() != null).map(r -> {
            PartCategory category = categoryById.get(r.getCategoryId());
            return new CategoryRate(r.getCategoryId(), category.getCategoryName(), category.getVehicleType(),
                    category.getParentId(), r.getMarkupRate());
        }).sorted(Comparator.comparing(CategoryRate::vehicleType).thenComparing(CategoryRate::categoryName)).toList();
        List<ProductRate> productRates = rows.stream().filter(r -> r.getProductId() != null).map(r -> {
            Product product = productById.get(r.getProductId());
            return new ProductRate(r.getProductId(), product.getSku(), product.getPartName(), r.getMarkupRate());
        }).sorted(Comparator.comparing(ProductRate::sku)).toList();

        PricingPolicy first = rows.getFirst();
        return new MarkupConfiguration(first.getEffectiveFrom(), MarkupRules.of(rows).rounding(), categoryRates,
                productRates, first.getSetBy(), userNames(List.of(first)).get(first.getSetBy()), first.getSetAt());
    }

    /** The rates as rules, one per category and per part (MSG127). */
    private static MarkupRules rulesOf(RoundingRule rounding, List<CategoryMarkup> categoryRates,
                                       List<ProductMarkup> productRates) {
        Map<Long, BigDecimal> byCategory = new HashMap<>();
        for (CategoryMarkup rate : categoryRates) {
            if (byCategory.put(rate.categoryId(), rate.markupRate()) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG127");
            }
        }
        Map<Long, BigDecimal> byProduct = new HashMap<>();
        for (ProductMarkup rate : productRates) {
            if (byProduct.put(rate.productId(), rate.markupRate()) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG127");
            }
        }
        return new MarkupRules(null, rounding, byCategory, byProduct);
    }

    /** Every category (MSG61) and part (MSG57) of the rules exists. */
    private void requireKnown(MarkupRules rules) {
        if (categories.findAllById(rules.byCategory().keySet()).size() != rules.byCategory().size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG61");
        }
        if (products.findAllById(rules.byProduct().keySet()).size() != rules.byProduct().size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG57");
        }
    }

    /** One configuration as kept in audit_log. */
    private static Map<String, Object> values(List<PricingPolicy> rows) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("effective_from", rows.getFirst().getEffectiveFrom().toString());
        values.put("rounding_rule", MarkupRules.of(rows).rounding().name());
        values.put("categories", rows.stream().filter(r -> r.getCategoryId() != null)
                .map(r -> Map.<String, Object>of("category_id", r.getCategoryId(), "markup_rate", r.getMarkupRate()))
                .toList());
        values.put("products", rows.stream().filter(r -> r.getProductId() != null)
                .map(r -> Map.<String, Object>of("product_id", r.getProductId(), "markup_rate", r.getMarkupRate()))
                .toList());
        return values;
    }

    private Map<Long, String> userNames(List<PricingPolicy> versions) {
        return users.findAllById(versions.stream().map(PricingPolicy::getSetBy).distinct().toList()).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
    }
}
