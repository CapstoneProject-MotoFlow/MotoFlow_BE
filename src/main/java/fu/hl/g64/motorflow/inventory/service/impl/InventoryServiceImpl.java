package fu.hl.g64.motorflow.inventory.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.catalog.service.PartCategoryService;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.LotDetailResponse;
import fu.hl.g64.motorflow.inventory.dto.LotDetailResponse.IssueOrderLine;
import fu.hl.g64.motorflow.inventory.dto.LotDetailResponse.SourceDocument;
import fu.hl.g64.motorflow.inventory.dto.LotDetailResponse.StatusQty;
import fu.hl.g64.motorflow.inventory.dto.LotListResponse;
import fu.hl.g64.motorflow.inventory.dto.LotRowResponse;
import fu.hl.g64.motorflow.inventory.dto.LotSearch;
import fu.hl.g64.motorflow.inventory.dto.LowStockThresholdRequest;
import fu.hl.g64.motorflow.inventory.dto.MovementSearch;
import fu.hl.g64.motorflow.inventory.dto.StockAvailabilityResponse;
import fu.hl.g64.motorflow.inventory.dto.StockDetailResponse;
import fu.hl.g64.motorflow.inventory.dto.StockDetailResponse.ExpiryState;
import fu.hl.g64.motorflow.inventory.dto.StockDetailResponse.LotLine;
import fu.hl.g64.motorflow.inventory.dto.StockMovementResponse;
import fu.hl.g64.motorflow.inventory.dto.StockRowResponse;
import fu.hl.g64.motorflow.inventory.dto.StockSearch;
import fu.hl.g64.motorflow.inventory.dto.StorageLocationRequest;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.LotBalance;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.inventory.entity.StockMovement;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.LotStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.LotQueryRepository;
import fu.hl.g64.motorflow.inventory.repository.LotQueryRepository.LotStock;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockMovementRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryService;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private static final Set<MovementType> FIRST_RECEIPT = Set.of(MovementType.RECEIPT_CONFIRMED,
            MovementType.EXCESS_ACCEPTED, MovementType.OPENING_BALANCE, MovementType.COUNT_ADJUSTMENT);
    private static final int ISSUE_ORDER_LINES = 20;

    private final StockBalanceRepository balances;
    private final LotBalanceRepository lotBalances;
    private final LotQueryRepository lotQueries;
    private final LotRepository lots;
    private final StockMovementRepository movements;
    private final ProductRepository products;
    private final PartCategoryService categories;
    private final StoreRepository stores;
    private final SupplierRepository suppliers;
    private final UserAccountRepository users;
    private final PricingPolicyRepository policies;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final StoreScope storeScope;
    private final Clock clock;

    // UC-47
    @Transactional(readOnly = true)
    @Override
    public PageResponse<StockRowResponse> listStock(AuthenticatedUser user, StockSearch filter, int page, int size) {
        Long storeId = scopeStoreId(user, filter.storeId());
        List<Long> categoryIds = filter.categoryId() == null ? null : categories.withDescendants(filter.categoryId());
        Specification<StockBalance> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (storeId != null) {
                where.add(cb.equal(root.get("storeId"), storeId));
            }
            if (StringUtils.hasText(filter.q()) || filter.categoryId() != null) {
                Subquery<Long> matching = query.subquery(Long.class);
                var product = matching.from(Product.class);
                List<Predicate> on = new ArrayList<>();
                if (StringUtils.hasText(filter.q())) {
                    String like = "%" + filter.q().strip().toLowerCase(Locale.ROOT) + "%";
                    on.add(cb.or(cb.like(cb.lower(product.get("sku")), like),
                            cb.like(cb.lower(product.get("partName")), like)));
                }
                if (filter.categoryId() != null) {
                    on.add(product.get("categoryId").in(categoryIds));
                }
                matching.select(product.get("id")).where(on.toArray(Predicate[]::new));
                where.add(root.get("productId").in(matching));
            }
            if (filter.lowStockOnly()) {
                where.add(cb.isNotNull(root.get("lowStockThreshold")));
                where.add(cb.lessThan(root.get("availableQty"), root.<Integer>get("lowStockThreshold")));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<StockBalance> found = balances.findAll(spec, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by("storeId", "productId")));
        return PageResponse.of(found, rows(user, found.getContent()));
    }

    // UC-47
    @Transactional(readOnly = true)
    @Override
    public StockDetailResponse stockDetail(AuthenticatedUser user, long storeId, long productId) {
        scopeStoreId(user, storeId);
        StockBalance balance = balances.findByStoreIdAndProductId(storeId, productId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG89"));
        boolean owner = user.role() == Role.OWNER;
        LocalDate today = LocalDate.now(clock);
        Integer warnDays = expiryWarningDays();
        List<LotBalance> held = lotBalances.findHeldByStoreAndProduct(storeId, productId);
        Map<Long, Lot> lotById = lots.findAllById(held.stream().map(LotBalance::getLotId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Lot::getId, Function.identity()));
        Map<Long, String> supplierNames = suppliers.findAllById(lotById.values().stream().map(Lot::getSupplierId)
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getSupplierName));
        List<LotLine> lines = held.stream().map(b -> {
            Lot lot = lotById.get(b.getLotId());
            return new LotLine(lot.getId(), lot.getSourceType(), lot.getReceivedDate(), lot.getSupplierId(),
                    supplierNames.get(lot.getSupplierId()), lot.getManufacturerLotNo(), lot.getExpiryDate(),
                    expiryState(lot, today, warnDays), b.getStockStatus(), b.getQuantity(),
                    owner ? lot.getPurchaseUnitPrice() : null);
        }).toList();
        Product product = products.findById(productId).orElseThrow();
        return new StockDetailResponse(rows(user, List.of(balance)).getFirst(),
                owner ? product.getMovingAvgCost() : null, warnDays, lines);
    }

    // UC-48
    @Transactional(readOnly = true)
    @Override
    public List<StockAvailabilityResponse> availability(AuthenticatedUser user, Collection<Long> productIds) {
        if (!storeScope.seesEveryStore(user)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Store> active = stores.findAll().stream().filter(s -> s.getStatus() == StoreStatus.ACTIVE)
                .collect(Collectors.toMap(Store::getId, Function.identity()));
        Map<Long, Product> productById = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<StockBalance> found = balances.findByProductIdIn(productById.keySet()).stream()
                .filter(b -> active.containsKey(b.getStoreId())).toList();
        Map<String, int[]> expiry = expiryTotals(active.keySet(), productById.keySet());
        return found.stream()
                .map(b -> {
                    int expired = expiry.getOrDefault(key(b.getStoreId(), b.getProductId()), new int[2])[0];
                    Store store = active.get(b.getStoreId());
                    return new StockAvailabilityResponse(b.getProductId(), productById.get(b.getProductId()).getSku(),
                            store.getId(), store.getStoreName(), store.isCentral(), b.getAvailableQty() - expired);
                })
                .filter(r -> r.sellableQty() > 0)
                .sorted(Comparator.comparing(StockAvailabilityResponse::sku)
                        .thenComparing(Comparator.comparingInt(StockAvailabilityResponse::sellableQty).reversed()))
                .toList();
    }

    // UC-49
    @Transactional(readOnly = true)
    @Override
    public PageResponse<StockMovementResponse> movements(AuthenticatedUser user, MovementSearch filter, int page,
                                                         int size) {
        Long storeId = scopeStoreId(user, filter.storeId());
        Specification<StockMovement> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (storeId != null) {
                where.add(cb.equal(root.get("storeId"), storeId));
            }
            if (filter.productId() != null) {
                where.add(cb.equal(root.get("productId"), filter.productId()));
            }
            if (filter.lotId() != null) {
                where.add(cb.equal(root.get("lotId"), filter.lotId()));
            }
            if (filter.movementType() != null) {
                where.add(cb.equal(root.get("movementType"), filter.movementType()));
            }
            if (filter.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from().atStartOfDay()));
            }
            if (filter.to() != null) {
                where.add(cb.lessThan(root.get("occurredAt"), filter.to().plusDays(1).atStartOfDay()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<StockMovement> found = movements.findAll(spec, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))));
        return PageResponse.of(found, movementRows(user, found.getContent()));
    }

    // UC-50
    @Transactional
    @Override
    public StockRowResponse updateLocation(AuthenticatedUser user, long storeId, long productId,
                                           StorageLocationRequest request) {
        StockBalance balance = editableBalance(user, storeId, productId);
        balance.moveTo(blankToNull(request.storageArea()), blankToNull(request.binLocation()), LocalDateTime.now(clock));
        return rows(user, List.of(balance)).getFirst();
    }

    // UC-51
    @Transactional
    @Override
    public StockRowResponse updateLowStockThreshold(AuthenticatedUser user, long storeId, long productId,
                                                    LowStockThresholdRequest request) {
        StockBalance balance = editableBalance(user, storeId, productId);
        balance.setLowStockThreshold(request.lowStockThreshold(), LocalDateTime.now(clock));
        return rows(user, List.of(balance)).getFirst();
    }

    // UC-97
    @Transactional(readOnly = true)
    @Override
    public LotListResponse listLots(AuthenticatedUser user, LotSearch filter, int page, int size) {
        Long storeId = scopeStoreId(user, filter.storeId());
        LocalDate today = LocalDate.now(clock);
        Integer warnDays = expiryWarningDays();
        // Without a configured warning period the near-expiry window is empty.
        LocalDate warnUntil = warnDays == null ? today.minusDays(1) : today.plusDays(warnDays);
        String q = StringUtils.hasText(filter.q()) ? filter.q().strip() : null;
        String like = q == null ? null : "%" + q.toLowerCase(Locale.ROOT) + "%";
        Page<LotStock> found = lotQueries.searchLots(filter.tab().name(), storeId, filter.supplierId(), filter.source(),
                filter.receivedFrom(), filter.receivedTo(), q, like, today, warnUntil,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
        return new LotListResponse(warnDays, PageResponse.of(found, found.getContent().stream().map(row -> {
            Lot lot = row.getLot();
            Product p = row.getProduct();
            return new LotRowResponse(lot.getId(), row.getStoreId(), row.getStoreName(), p.getId(), p.getSku(),
                    p.getPartName(), p.getBaseUnit(), lot.getSourceType(), lot.getSupplierId(), row.getSupplierName(),
                    lot.getManufacturerLotNo(), lot.getManufactureDate(), lot.getExpiryDate(), daysToExpiry(lot, today),
                    row.getRemaining(), lot.getReceivedDate());
        }).toList()));
    }

    // UC-98
    @Transactional(readOnly = true)
    @Override
    public LotDetailResponse lotDetail(AuthenticatedUser user, long lotId, Long storeId) {
        Long scoped = scopeStoreId(user, storeId);
        Lot lot = lots.findById(lotId).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG90"));
        long viewStoreId = scoped != null ? scoped : lot.getStoreId();
        boolean owner = user.role() == Role.OWNER;
        List<LotBalance> held = lotBalances.findByLotIdAndStoreIdOrderByStockStatusAsc(lotId, viewStoreId);
        if (!owner && held.isEmpty() && !movements.existsByLotIdAndStoreId(lotId, viewStoreId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        LocalDate today = LocalDate.now(clock);
        int remaining = held.stream().filter(b -> b.getStockStatus() != StockStatus.IN_TRANSIT)
                .mapToInt(LotBalance::getQuantity).sum();
        Product product = products.findById(lot.getProductId()).orElseThrow();
        return new LotDetailResponse(lot.getId(),
                remaining == 0 ? "DEPLETED" : expiryState(lot, today, expiryWarningDays()).name(),
                daysToExpiry(lot, today), product.getId(), product.getSku(), product.getPartName(), product.getBaseUnit(),
                lot.getSourceType(), lot.getSupplierId(),
                lot.getSupplierId() == null ? null : suppliers.findById(lot.getSupplierId()).orElseThrow().getSupplierName(),
                sourceDocument(lot), lot.getStoreId(), stores.findById(lot.getStoreId()).orElseThrow().getStoreName(),
                lot.getReceivedDate(), lot.getManufacturerLotNo(), lot.getManufactureDate(), lot.getExpiryDate(),
                movements.sumIncoming(lotId, lot.getStoreId(), FIRST_RECEIPT), viewStoreId, remaining,
                held.stream().filter(b -> b.getQuantity() > 0)
                        .map(b -> new StatusQty(b.getStockStatus(), b.getQuantity())).toList(),
                movementRows(user, movements.findTop10ByLotIdAndStoreIdOrderByOccurredAtDescIdDesc(lotId, viewStoreId)),
                lotQueries.issueOrder(lot.getProductId(), viewStoreId, lotId, Limit.of(ISSUE_ORDER_LINES)).stream()
                        .map(line -> new IssueOrderLine(line.getLot().getId(), line.getLot().getExpiryDate(),
                                line.getLot().getReceivedDate(), line.getAvailable(), line.getLot().getId().equals(lotId),
                                line.getLot().isExpiredOn(today) || line.getAvailable() == 0))
                        .toList(),
                owner ? lot.getPurchaseUnitPrice() : null);
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * The warehouse a user may read: null means every warehouse (Owner without a filter). A central warehouse Branch
     * Manager is kept to their own too: only UC-48, while creating a transfer order, shows them others (BR-03).
     */
    private Long scopeStoreId(AuthenticatedUser user, Long requested) {
        if (user.role() == Role.OWNER) {
            if (requested != null && !stores.existsById(requested)) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "MSG52");
            }
            return requested;
        }
        if (user.storeId() == null || (requested != null && !requested.equals(user.storeId()))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return user.storeId();
    }

    private SourceDocument sourceDocument(Lot lot) {
        return lot.getSourceType() == LotSourceType.RECEIPT
                ? new SourceDocument("GOODS_RECEIPT_NOTE",
                        receiptLines.findById(lot.getGoodsReceiptNoteLineId()).orElseThrow().getGoodsReceiptNoteId())
                : new SourceDocument("STOCK_COUNT", lot.getStockCountId());
    }

    /** Unit cost and value are filled for the Owner only (BR-72). */
    private List<StockMovementResponse> movementRows(AuthenticatedUser user, List<StockMovement> content) {
        Map<Long, String> skus = products.findAllById(content.stream().map(StockMovement::getProductId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Product::getId, Product::getSku));
        Map<Long, String> names = users.findAllById(content.stream().map(StockMovement::getPerformedBy)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        boolean owner = user.role() == Role.OWNER;
        return content.stream().map(m -> new StockMovementResponse(m.getId(), m.getOccurredAt(),
                m.getStoreId(), m.getProductId(), skus.get(m.getProductId()), m.getLotId(), m.getMovementType(),
                m.getStockStatus(), m.getQuantityChange(), m.source().type(), m.source().id(), m.getReason(),
                m.getPerformedBy(), names.get(m.getPerformedBy()), owner ? m.getUnitCost() : null,
                owner ? m.getValue() : null)).toList();
    }

    /** UC-50, UC-51: a Branch Manager changes their own warehouse, the Owner any warehouse. */
    private StockBalance editableBalance(AuthenticatedUser user, long storeId, long productId) {
        if (user.role() != Role.OWNER && !Long.valueOf(storeId).equals(user.storeId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        if (!stores.existsById(storeId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG52");
        }
        if (!products.existsById(productId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG57");
        }
        balances.insertIfMissing(storeId, productId, LocalDateTime.now(clock));
        return balances.findForUpdate(storeId, productId).orElseThrow();
    }

    private List<StockRowResponse> rows(AuthenticatedUser user, List<StockBalance> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        Set<Long> storeIds = page.stream().map(StockBalance::getStoreId).collect(Collectors.toSet());
        Set<Long> productIds = page.stream().map(StockBalance::getProductId).collect(Collectors.toSet());
        Map<Long, String> storeNames = stores.findAllById(storeIds).stream()
                .collect(Collectors.toMap(Store::getId, Store::getStoreName));
        Map<Long, Product> productById = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<String, int[]> expiry = expiryTotals(storeIds, productIds);
        boolean owner = user.role() == Role.OWNER;
        return page.stream().map(b -> {
            Product p = productById.get(b.getProductId());
            int[] e = expiry.getOrDefault(key(b.getStoreId(), b.getProductId()), new int[2]);
            Long value = owner && p.getMovingAvgCost() != null
                    ? p.getMovingAvgCost().multiply(BigDecimal.valueOf(b.valuedQty())).setScale(0, RoundingMode.HALF_UP).longValue()
                    : null;
            return new StockRowResponse(b.getStoreId(), storeNames.get(b.getStoreId()), p.getId(), p.getSku(),
                    p.getPartName(), p.getBaseUnit(), b.getStorageArea(), b.getBinLocation(), b.getLowStockThreshold(),
                    b.getAvailableQty(), b.getAwaitingInspectionQty(), b.getWrongItemQty(), b.getDamagedQty(),
                    b.getAwaitingSupplierReturnQty(), b.getInTransitQty(), e[1], e[0], b.isLowStock(), value);
        }).toList();
    }

    /** "store:product" → [expired, near expiry] available quantities. */
    private Map<String, int[]> expiryTotals(Collection<Long> storeIds, Collection<Long> productIds) {
        if (storeIds.isEmpty() || productIds.isEmpty()) {
            return Map.of();
        }
        LocalDate today = LocalDate.now(clock);
        Integer days = expiryWarningDays();
        // Without a configured warning period nothing is "near expiry"; expired lots are still reported.
        LocalDate warnUntil = days == null ? today.minusDays(1) : today.plusDays(days);
        Map<String, int[]> totals = new HashMap<>();
        for (Object[] row : lotBalances.sumExpiryByStoreAndProduct(storeIds, productIds, today, warnUntil)) {
            totals.put(key((Long) row[0], (Long) row[1]),
                    new int[]{((Number) row[2]).intValue(), ((Number) row[3]).intValue()});
        }
        return totals;
    }

    /** UC-107 setting in effect, or null if the Owner has not set one. */
    private Integer expiryWarningDays() {
        return policies.findExpiryWarningsInEffect(LocalDateTime.now(clock)).stream()
                .findFirst().map(PricingPolicy::getExpiryWarningDays).orElse(null);
    }

    private static ExpiryState expiryState(Lot lot, LocalDate today, Integer warnDays) {
        if (lot.getStatus() == LotStatus.EXPIRED || (lot.getExpiryDate() != null && lot.getExpiryDate().isBefore(today))) {
            return ExpiryState.EXPIRED;
        }
        if (lot.getExpiryDate() == null) {
            return ExpiryState.NO_EXPIRY;
        }
        return warnDays != null && !lot.getExpiryDate().isAfter(today.plusDays(warnDays))
                ? ExpiryState.NEAR_EXPIRY : ExpiryState.OK;
    }

    private static Integer daysToExpiry(Lot lot, LocalDate today) {
        return lot.getExpiryDate() == null ? null : Math.toIntExact(ChronoUnit.DAYS.between(today, lot.getExpiryDate()));
    }

    private static String key(Long storeId, Long productId) {
        return storeId + ":" + productId;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
