package fu.hl.g64.motorflow.pricing.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.PartCategory;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.PartCategoryRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.catalog.service.PartCategoryService;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.pricing.dto.PriceVersionResponse;
import fu.hl.g64.motorflow.pricing.dto.RetailPriceRequest;
import fu.hl.g64.motorflow.pricing.dto.RetailPriceResponse;
import fu.hl.g64.motorflow.pricing.dto.RetailPriceSearch;
import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.helper.PriceSuggestions;
import fu.hl.g64.motorflow.pricing.helper.PriceSuggestions.Suggestion;
import fu.hl.g64.motorflow.pricing.helper.PricingVersions;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import fu.hl.g64.motorflow.pricing.service.RetailPriceService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RetailPriceServiceImpl implements RetailPriceService {

    static final String AUDIT_ENTITY = "PRICING_POLICY";
    static final String PRICE_SET = "RETAIL_PRICE_SET";
    static final String SOURCE_TYPED = "TYPED";
    static final String SOURCE_SUGGESTION = "SUGGESTION";

    private static final Sort BY_SKU = Sort.by("sku");

    private final ProductRepository products;
    private final PartCategoryRepository categories;
    private final PartCategoryService categoryService;
    private final PricingPolicyRepository pricingPolicies;
    private final PriceSuggestions suggestions;
    private final AuditLogRepository auditLog;
    private final UserAccountRepository users;
    private final Clock clock;

    // UC-34
    @Transactional(readOnly = true)
    @Override
    public PageResponse<RetailPriceResponse> list(AuthenticatedUser user, RetailPriceSearch filter, int page, int size) {
        boolean onlyDiffering = Boolean.TRUE.equals(filter.suggestionDiffers());
        if (onlyDiffering && !seesSuggestions(user)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int pageNo = Math.max(page, 0);
        int pageSize = Math.clamp(size, 1, 100);
        if (!onlyDiffering) {
            Page<Product> found = products.findAll(matching(filter), PageRequest.of(pageNo, pageSize, BY_SKU));
            return PageResponse.of(found, rows(user, found.getContent(), now));
        }

        // Suggested prices are worked out, not stored, so this filter goes through every matching part.
        List<Product> matches = products.findAll(matching(filter), BY_SKU);
        List<RetailPriceResponse> differing = new ArrayList<>();
        for (int from = 0; from < matches.size(); from += PriceSuggestions.CHUNK) {
            rows(user, matches.subList(from, Math.min(from + PriceSuggestions.CHUNK, matches.size())), now).stream()
                    .filter(RetailPriceResponse::suggestionDiffers)
                    .forEach(differing::add);
        }
        int from = (int) Math.min((long) pageNo * pageSize, differing.size());
        int to = Math.min(from + pageSize, differing.size());
        return new PageResponse<>(differing.subList(from, to), differing.size(), pageNo, pageSize);
    }

    // UC-34
    @Transactional(readOnly = true)
    @Override
    public RetailPriceResponse get(AuthenticatedUser user, long productId) {
        return rows(user, List.of(find(productId)), LocalDateTime.now(clock)).getFirst();
    }

    // UC-35
    @Transactional
    @Override
    public RetailPriceResponse update(AuthenticatedUser actor, RetailPriceRequest request) {
        pricingPolicies.lockSetting("RETAIL_PRICE:" + request.productId());
        Product product = find(request.productId());
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = PricingVersions.effectiveFrom(request.effectiveDate(), now);
        if (request.fromSuggestion()) {
            // The suggestion moves with each supplier receipt and markup change: apply only the one the user saw.
            Suggestion suggestion = suggestions.suggest(List.of(product), suggestions.rulesInEffect(now))
                    .get(product.getId());
            if (suggestion.suggestedPrice() == null) {
                // No supplier receipt yet, or no markup rate applies (BR-69): there is nothing to apply.
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG145", product.getSku());
            }
            if (!request.unitPrice().equals(suggestion.suggestedPrice())) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG126", product.getSku());
            }
        }

        List<PricingPolicy> replaced = PricingVersions.replacedBy(from,
                pricingPolicies.findRetailPriceVersions(product.getId()), now);
        if (!replaced.isEmpty()) {
            // Deleted at once, before the insert, because the new version may take the same date.
            pricingPolicies.deleteAllInBatch(replaced);
        }
        PricingPolicy saved = pricingPolicies.save(
                PricingPolicy.retailPrice(product.getId(), request.unitPrice(), from, actor.id(), now));

        Map<String, Object> newData = new LinkedHashMap<>();
        newData.put("product_id", product.getId());
        newData.put("unit_price", saved.getUnitPrice());
        newData.put("effective_from", from.toString());
        newData.put("source", request.fromSuggestion() ? SOURCE_SUGGESTION : SOURCE_TYPED);
        Map<String, Object> oldData = replaced.isEmpty() ? null : Map.of("replaced_scheduled", replaced.stream()
                .map(v -> Map.<String, Object>of("unit_price", v.getUnitPrice(),
                        "effective_from", v.getEffectiveFrom().toString()))
                .toList());
        auditLog.save(AuditLog.record(AUDIT_ENTITY, saved.getId(), PRICE_SET, actor.id(), blankToNull(request.reason()),
                oldData, newData, now));
        return rows(actor, List.of(product), now).getFirst();
    }

    // UC-36
    @Transactional(readOnly = true)
    @Override
    public List<PriceVersionResponse> history(long productId) {
        find(productId);
        List<PricingPolicy> versions = pricingPolicies.findRetailPriceVersions(productId);
        if (versions.isEmpty()) {
            return List.of();
        }
        Map<Long, AuditLog> changes = auditLog.findByEntityTypeAndEntityIdInAndAction(AUDIT_ENTITY,
                        versions.stream().map(PricingPolicy::getId).toList(), PRICE_SET).stream()
                .collect(Collectors.toMap(AuditLog::getEntityId, Function.identity(), (first, second) -> first));
        Map<Long, String> names = users.findAllById(versions.stream().map(PricingPolicy::getSetBy).distinct().toList())
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        Map<Long, Long> previousPrice = new HashMap<>();
        for (int i = 0; i + 1 < versions.size(); i++) {
            previousPrice.put(versions.get(i).getId(), versions.get(i + 1).getUnitPrice());
        }

        return PricingVersions.timeline(versions, LocalDateTime.now(clock), (version, effectiveTo, state) -> {
            AuditLog change = changes.get(version.getId());
            Long previous = previousPrice.get(version.getId());
            return new PriceVersionResponse(version.getId(), version.getUnitPrice(),
                    previous == null ? null : version.getUnitPrice() - previous, version.getEffectiveFrom(),
                    effectiveTo, state,
                    change == null ? null : SOURCE_SUGGESTION.equals(sourceOf(change)),
                    change == null ? null : change.getReason(), version.getSetBy(), names.get(version.getSetBy()),
                    version.getSetAt());
        });
    }

    /** The rows of the price list for these parts, in their order. */
    private List<RetailPriceResponse> rows(AuthenticatedUser user, List<Product> parts, LocalDateTime now) {
        if (parts.isEmpty()) {
            return List.of();
        }
        List<Long> ids = parts.stream().map(Product::getId).toList();
        Map<Long, PricingPolicy> current = pricingPolicies.findRetailPricesInEffect(ids, now).stream()
                .collect(Collectors.toMap(PricingPolicy::getProductId, Function.identity(), (older, newer) -> newer));
        Map<Long, PricingPolicy> scheduled = pricingPolicies.findScheduledRetailPrices(ids, now).stream()
                .collect(Collectors.toMap(PricingPolicy::getProductId, Function.identity(),
                        (a, b) -> a.getEffectiveFrom().isBefore(b.getEffectiveFrom()) ? a : b));
        Map<Long, String> categoryNames = categories.findAllById(parts.stream().map(Product::getCategoryId)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PartCategory::getId, PartCategory::getCategoryName));
        Map<Long, Suggestion> suggested = seesSuggestions(user)
                ? suggestions.suggest(parts, suggestions.rulesInEffect(now))
                : Map.of();
        boolean costShown = user.role() == Role.OWNER;
        return parts.stream().map(p -> RetailPriceResponse.of(p, categoryNames.get(p.getCategoryId()),
                current.get(p.getId()), scheduled.get(p.getId()), suggested.get(p.getId()), costShown)).toList();
    }

    private Specification<Product> matching(RetailPriceSearch filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (StringUtils.hasText(filter.q())) {
                String like = "%" + filter.q().strip().toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("sku")), like), cb.like(cb.lower(root.get("partName")), like)));
            }
            if (filter.categoryId() != null) {
                where.add(root.get("categoryId").in(categoryService.withDescendants(filter.categoryId())));
            }
            if (filter.status() != null) {
                where.add(cb.equal(root.get("status"), filter.status()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** UC-34, BR-69: Owner, and a Branch Manager while holding retail price permission. */
    private static boolean seesSuggestions(AuthenticatedUser user) {
        return user.role() == Role.OWNER || (user.role() == Role.BRANCH_MANAGER
                && user.permissions().contains(PermissionCode.RETAIL_PRICE_EDIT));
    }

    private static Object sourceOf(AuditLog change) {
        return change.getNewData() == null ? null : change.getNewData().get("source");
    }

    private Product find(long productId) {
        return products.findById(productId).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57"));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
