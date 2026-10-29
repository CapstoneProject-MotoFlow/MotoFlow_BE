package fu.hl.g64.motorflow.catalog.service;

import fu.hl.g64.motorflow.catalog.dto.ProductRequest;
import fu.hl.g64.motorflow.catalog.dto.ProductResponse;
import fu.hl.g64.motorflow.catalog.dto.ProductSearch;
import fu.hl.g64.motorflow.catalog.dto.ProductSummaryResponse;
import fu.hl.g64.motorflow.catalog.dto.VehicleModelResponse;
import fu.hl.g64.motorflow.catalog.entity.PartCategory;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.ProductIdentifier;
import fu.hl.g64.motorflow.catalog.entity.ProductImage;
import fu.hl.g64.motorflow.catalog.entity.ProductVehicleModel;
import fu.hl.g64.motorflow.catalog.entity.VehicleModel;
import fu.hl.g64.motorflow.catalog.entity.enums.IdentifierType;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleModelStatus;
import fu.hl.g64.motorflow.catalog.repository.PartCategoryRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductIdentifierRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductImageRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductVehicleModelRepository;
import fu.hl.g64.motorflow.catalog.repository.VehicleModelRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.service.StoreScope;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** UC-13 Search, UC-14 View, UC-15 Create, UC-16 Update and UC-17 Discontinue Spare Part. */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository products;
    private final PartCategoryRepository categories;
    private final PartCategoryService categoryService;
    private final VehicleModelRepository vehicleModels;
    private final ProductIdentifierRepository identifiers;
    private final ProductImageRepository images;
    private final ProductVehicleModelRepository compatibility;
    private final PricingPolicyRepository pricingPolicies;
    private final StockBalanceRepository stockBalances;
    private final StoreScope storeScope;
    private final AuditLogRepository auditLog;
    private final Clock clock;

    /** By SKU. Stock and storage location are those of the viewed store (BR-03). */
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryResponse> search(AuthenticatedUser user, ProductSearch filter, int page, int size) {
        Store store = storeScope.viewableStore(user, filter.storeId());
        Page<Product> found = products.findAll(matching(filter, store),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("sku")));
        List<Long> ids = found.map(Product::getId).getContent();
        if (ids.isEmpty()) {
            return PageResponse.of(found, List.of());
        }

        Map<Long, String> categoryNames = categories.findAllById(found.map(Product::getCategoryId).toSet()).stream()
                .collect(Collectors.toMap(PartCategory::getId, PartCategory::getCategoryName));
        Map<Long, List<String>> oemCodes = identifiers.findByProductIdIn(ids).stream()
                .filter(i -> i.getIdentifierType() == IdentifierType.OEM)
                .collect(Collectors.groupingBy(ProductIdentifier::getProductId,
                        Collectors.mapping(ProductIdentifier::getIdentifierValue, Collectors.toList())));
        Map<Long, Long> prices = retailPricesInEffect(ids);
        Map<Long, StockBalance> stock = store == null ? Map.of()
                : stockBalances.findByStoreIdAndProductIdIn(store.getId(), ids).stream()
                .collect(Collectors.toMap(StockBalance::getProductId, Function.identity()));

        return PageResponse.of(found, found.getContent().stream().map(p -> {
            StockBalance balance = stock.get(p.getId());
            return new ProductSummaryResponse(p.getId(), p.getSku(), p.getPartName(), p.getBrand(), p.getBaseUnit(),
                    p.getCategoryId(), categoryNames.get(p.getCategoryId()), oemCodes.getOrDefault(p.getId(), List.of()),
                    p.getStatus(), prices.get(p.getId()),
                    store == null ? null : balance == null ? 0 : balance.getAvailableQty(),
                    balance == null ? null : balance.getStorageArea(), balance == null ? null : balance.getBinLocation());
        }).toList());
    }

    @Transactional(readOnly = true)
    public ProductResponse detail(AuthenticatedUser user, long id, Long storeId) {
        Product product = find(id);
        Store store = storeScope.viewableStore(user, storeId);

        List<ProductIdentifier> codes = identifiers.findByProductIdIn(List.of(id));
        List<String> imageUrls = images.findByProductIdIn(List.of(id)).stream()
                .sorted(Comparator.comparingInt(ProductImage::getSortOrder)).map(ProductImage::getStorageKey).toList();
        List<VehicleModelResponse> models = vehicleModels.findAllById(compatibility.findByProductIdIn(List.of(id)).stream()
                        .map(ProductVehicleModel::getVehicleModelId).toList()).stream()
                .sorted(Comparator.comparing(VehicleModel::getManufacturer).thenComparing(VehicleModel::getModelName))
                .map(VehicleModelResponse::from).toList();
        ProductResponse.Stock stock = store == null ? null : ProductResponse.Stock.of(store.getId(),
                stockBalances.findByStoreIdAndProductIdIn(store.getId(), List.of(id)).stream().findFirst().orElse(null));

        return new ProductResponse(product.getId(), product.getSku(), product.getPartName(), product.getCategoryId(),
                categories.findById(product.getCategoryId()).map(PartCategory::getCategoryName).orElse(null),
                product.getBrand(), product.getCountryOfOrigin(), product.getBaseUnit(), product.getSpecifications(),
                product.getStatus(), product.getDiscontinuedAt(), values(codes, IdentifierType.OEM),
                values(codes, IdentifierType.BARCODE), imageUrls, models, retailPricesInEffect(List.of(id)).get(id),
                stock, product.getCreatedAt(), product.getUpdatedAt());
    }

    /** UC-15. Creates no stock (DBR-02); prices are set afterwards in SCR-25. */
    @Transactional
    public ProductResponse create(AuthenticatedUser actor, ProductRequest request) {
        if (!StringUtils.hasText(request.sku())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        String sku = request.sku().strip().toUpperCase(Locale.ROOT);
        if (products.existsBySku(sku)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG53", sku);
        }
        categoryService.requireLeafForParts(request.categoryId());
        requireUsableDetails(null, request, Set.of());

        LocalDateTime now = LocalDateTime.now(clock);
        Product product = Product.create(sku, request.partName().strip(), request.categoryId(), request.brand().strip(),
                blankToNull(request.countryOfOrigin()), request.baseUnit().strip(), blankToNull(request.specifications()),
                actor.id(), now);
        try {
            product = products.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            // Another user created the same SKU a moment earlier.
            throw new BusinessException(HttpStatus.CONFLICT, "MSG53", sku);
        }
        replaceDetails(product.getId(), request, actor, now);
        auditLog.save(AuditLog.record("PRODUCT", product.getId(), "PRODUCT_CREATED", actor.id(), null, now));
        return detail(actor, product.getId(), null);
    }

    /**
     * UC-16. A category or vehicle model that has since been deactivated may stay on the part, but a newly
     * chosen one must be in use.
     */
    @Transactional
    public ProductResponse update(AuthenticatedUser actor, long id, ProductRequest request) {
        Product product = find(id);
        if (!request.categoryId().equals(product.getCategoryId())) {
            categoryService.requireLeafForParts(request.categoryId());
        }
        Set<Long> linkedModels = compatibility.findByProductIdIn(List.of(id)).stream()
                .map(ProductVehicleModel::getVehicleModelId).collect(Collectors.toSet());
        requireUsableDetails(id, request, linkedModels);

        LocalDateTime now = LocalDateTime.now(clock);
        product.update(request.partName().strip(), request.categoryId(), request.brand().strip(),
                blankToNull(request.countryOfOrigin()), request.baseUnit().strip(), blankToNull(request.specifications()), now);
        replaceDetails(id, request, actor, now);
        auditLog.save(AuditLog.record("PRODUCT", id, "PRODUCT_UPDATED", actor.id(), null, now));
        return detail(actor, id, null);
    }

    /** UC-17 and its undo. Stock, prices and old documents are untouched (BR-07). */
    @Transactional
    public void changeStatus(AuthenticatedUser actor, long id, ProductStatus status) {
        Product product = find(id);
        if (product.getStatus() == status) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (status == ProductStatus.DISCONTINUED) {
            product.discontinue(now);
        } else {
            product.reactivate(now);
        }
        String action = status == ProductStatus.DISCONTINUED ? "PRODUCT_DISCONTINUED" : "PRODUCT_REACTIVATED";
        auditLog.save(AuditLog.record("PRODUCT", id, action, actor.id(), null, now));
    }

    private Specification<Product> matching(ProductSearch filter, Store store) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (StringUtils.hasText(filter.q())) {
                String like = "%" + filter.q().strip().toLowerCase(Locale.ROOT) + "%";
                Subquery<Long> byCode = query.subquery(Long.class);
                Root<ProductIdentifier> identifier = byCode.from(ProductIdentifier.class);
                byCode.select(identifier.get("productId")).where(cb.like(cb.lower(identifier.get("identifierValue")), like));
                where.add(cb.or(cb.like(cb.lower(root.get("sku")), like), cb.like(cb.lower(root.get("partName")), like),
                        root.get("id").in(byCode)));
            }
            if (filter.categoryId() != null) {
                where.add(root.get("categoryId").in(categoryService.withDescendants(filter.categoryId())));
            }
            if (StringUtils.hasText(filter.brand())) {
                where.add(cb.equal(cb.lower(root.get("brand")), filter.brand().strip().toLowerCase(Locale.ROOT)));
            }
            if (filter.vehicleType() != null) {
                Subquery<Long> ofType = query.subquery(Long.class);
                Root<PartCategory> category = ofType.from(PartCategory.class);
                ofType.select(category.get("id")).where(cb.equal(category.get("vehicleType"), filter.vehicleType()));
                where.add(root.get("categoryId").in(ofType));
            }
            if (filter.vehicleModelId() != null) {
                Subquery<Long> fits = query.subquery(Long.class);
                Root<ProductVehicleModel> link = fits.from(ProductVehicleModel.class);
                fits.select(link.get("productId")).where(cb.equal(link.get("vehicleModelId"), filter.vehicleModelId()));
                where.add(root.get("id").in(fits));
            }
            if (filter.status() != null) {
                where.add(cb.equal(root.get("status"), filter.status()));
            }
            if (Boolean.TRUE.equals(filter.inStockOnly()) && store != null) {
                Subquery<Long> sellable = query.subquery(Long.class);
                Root<StockBalance> balance = sellable.from(StockBalance.class);
                sellable.select(balance.get("productId")).where(cb.equal(balance.get("storeId"), store.getId()),
                        cb.greaterThan(balance.get("availableQty"), 0));
                where.add(root.get("id").in(sellable));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** Barcodes are unique across parts; vehicle models must be in use unless the part already had them. */
    private void requireUsableDetails(Long productId, ProductRequest request, Set<Long> linkedModels) {
        identifiers.findByIdentifierTypeAndIdentifierValueIn(IdentifierType.BARCODE, distinctStripped(request.barcodes()))
                .stream()
                .filter(other -> !other.getProductId().equals(productId))
                .findFirst()
                .ifPresent(other -> {
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG54", other.getIdentifierValue());
                });
        Map<Long, VehicleModel> models = vehicleModels.findAllById(request.vehicleModelIds()).stream()
                .collect(Collectors.toMap(VehicleModel::getId, Function.identity()));
        for (Long modelId : request.vehicleModelIds()) {
            VehicleModel model = models.get(modelId);
            if (model == null || (model.getStatus() != VehicleModelStatus.ACTIVE && !linkedModels.contains(modelId))) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG56");
            }
        }
    }

    private void replaceDetails(long productId, ProductRequest request, AuthenticatedUser actor, LocalDateTime now) {
        identifiers.deleteByProductId(productId);
        compatibility.deleteByProductId(productId);
        images.deleteByProductId(productId);

        List<ProductIdentifier> codes = new ArrayList<>();
        distinctStripped(request.oemCodes()).forEach(code -> codes.add(ProductIdentifier.of(productId, IdentifierType.OEM, code)));
        distinctStripped(request.barcodes()).forEach(code -> codes.add(ProductIdentifier.of(productId, IdentifierType.BARCODE, code)));
        identifiers.saveAll(codes);
        compatibility.saveAll(request.vehicleModelIds().stream().distinct()
                .map(modelId -> ProductVehicleModel.of(productId, modelId)).toList());
        List<String> urls = request.imageUrls().stream().map(String::strip).distinct().toList();
        images.saveAll(IntStream.range(0, urls.size())
                .mapToObj(i -> ProductImage.of(productId, urls.get(i), i, actor.id(), now)).toList());
    }

    /** The price of the newest ACTIVE version already in effect, per product (BR-09). */
    private Map<Long, Long> retailPricesInEffect(Collection<Long> productIds) {
        return pricingPolicies.findRetailPricesInEffect(productIds, LocalDateTime.now(clock)).stream()
                .collect(Collectors.toMap(PricingPolicy::getProductId, PricingPolicy::getUnitPrice, (older, newer) -> newer));
    }

    private Product find(long id) {
        return products.findById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57"));
    }

    private static List<String> values(List<ProductIdentifier> codes, IdentifierType type) {
        return codes.stream().filter(c -> c.getIdentifierType() == type).map(ProductIdentifier::getIdentifierValue).toList();
    }

    private static List<String> distinctStripped(List<String> values) {
        return values.stream().map(String::strip).distinct().toList();
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
