package fu.hl.g64.motorflow.store.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.StockBalanceService;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.dto.CopiedStoreResponse;
import fu.hl.g64.motorflow.store.dto.CopyStoreRequest;
import fu.hl.g64.motorflow.store.dto.CreateStoreRequest;
import fu.hl.g64.motorflow.store.dto.OwnerManagerRequest;
import fu.hl.g64.motorflow.store.dto.StoreEmployeeResponse;
import fu.hl.g64.motorflow.store.dto.StoreResponse;
import fu.hl.g64.motorflow.store.dto.UpdateStoreRequest;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService {

    private final StoreRepository stores;
    private final UserAccountRepository users;
    private final StockBalanceRepository stockBalances;
    private final StockBalanceService stockBalanceService;
    private final PricingPolicyRepository pricingPolicies;
    private final AuditLogRepository auditLog;
    private final Clock clock;

    @Transactional(readOnly = true)
    @Override
    public List<StoreResponse> listStores(String q, StoreStatus status, Boolean central) {
        Map<Long, List<UserAccount>> managersByStore = users.findByRoleAndStatus(Role.BRANCH_MANAGER, AccountStatus.ACTIVE)
                .stream().collect(Collectors.groupingBy(UserAccount::getStoreId));
        Map<Long, UserAccount> activeOwners = users.findByRoleAndStatus(Role.OWNER, AccountStatus.ACTIVE).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        Map<Long, Long> productsByStore = productsInStockByStore();
        String needle = StringUtils.hasText(q) ? q.strip().toLowerCase(Locale.ROOT) : null;

        return stores.findAll(Sort.by("id")).stream()
                .map(store -> StoreResponse.from(store,
                        withOwner(activeOwners.get(store.getOwnerManagerId()), managersByStore.getOrDefault(store.getId(), List.of())),
                        productsByStore.getOrDefault(store.getId(), 0L)))
                .filter(s -> status == null || s.status() == status)
                .filter(s -> central == null || s.central() == central)
                .filter(s -> needle == null || Stream.concat(
                                Stream.of(s.storeName(), String.valueOf(s.id()), s.address()),
                                s.managers().stream().map(StoreResponse.ManagerInfo::fullName))
                        .anyMatch(text -> text != null && text.toLowerCase(Locale.ROOT).contains(needle)))
                .toList();
    }

    @Transactional(readOnly = true)
    @Override
    public StoreResponse getStore(long storeId) {
        Store store = stores.findById(storeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
        return StoreResponse.from(store, managersOf(store), productsInStockByStore().getOrDefault(storeId, 0L));
    }

    @Transactional(readOnly = true)
    @Override
    public List<StoreEmployeeResponse> listEmployees(long storeId) {
        Store store = stores.findById(storeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
        Stream<UserAccount> owner = store.getOwnerManagerId() == null ? Stream.empty()
                : users.findById(store.getOwnerManagerId()).stream();
        return Stream.concat(owner, users.findByStoreId(storeId).stream())
                .sorted(Comparator.comparingInt((UserAccount u) -> rank(u.getRole()))
                        .thenComparing(UserAccount::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(StoreEmployeeResponse::from)
                .toList();
    }

    @Transactional
    @Override
    public StoreResponse setOwnerManager(AuthenticatedUser actor, long storeId, OwnerManagerRequest request) {
        Store store = stores.findById(storeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
        Long ownerId = request.ownerId();
        if (ownerId != null) {
            if (!store.acceptsStaff()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG46");
            }
            users.findById(ownerId)
                    .filter(u -> u.getRole() == Role.OWNER && u.getStatus() == AccountStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG82"));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        store.assignOwnerManager(ownerId, now);
        auditLog.save(AuditLog.record("STORE", storeId, ownerId == null ? "STORE_OWNER_MANAGER_REMOVED"
                : "STORE_OWNER_MANAGER_SET", actor.id(), ownerId == null ? null : "Chủ doanh nghiệp #" + ownerId, now));
        return StoreResponse.from(store, managersOf(store), productsInStockByStore().getOrDefault(storeId, 0L));
    }

    @Transactional
    @Override
    public StoreResponse createStore(AuthenticatedUser actor, CreateStoreRequest request) {
        String name = request.storeName().strip();
        if (stores.existsByStoreNameIgnoreCase(name)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG49", name);
        }
        if (request.central()) {
            requireNoOtherCentral(null);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String phone = blankToNull(request.phone());
        Store store = saveNew(Store.create(name, request.address().strip(), phone, request.central(), now));
        auditLog.save(AuditLog.record("STORE", store.getId(), "STORE_CREATED", actor.id(), null, now));
        return StoreResponse.from(store, List.of(), 0);
    }

    @Transactional
    @Override
    public CopiedStoreResponse copyStore(AuthenticatedUser actor, long templateId, CopyStoreRequest request) {
        Store template = stores.findById(templateId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
        String name = request.storeName().strip();
        if (stores.existsByStoreNameIgnoreCase(name)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG49", name);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String phone = blankToNull(request.phone());
        Store store = saveNew(Store.copyOf(template, name, request.address().strip(), phone, now));
        int copied = stockBalanceService.copySettings(templateId, store.getId(), now);
        auditLog.save(AuditLog.record("STORE", store.getId(), "STORE_COPIED", actor.id(),
                "Sao chép cấu hình từ cửa hàng #" + templateId, now));
        return new CopiedStoreResponse(StoreResponse.from(store, List.of(), 0), copied);
    }

    @Transactional
    @Override
    public StoreResponse updateStore(AuthenticatedUser actor, long storeId, UpdateStoreRequest request) {
        Store store = stores.findById(storeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
        boolean isCentral = store.isCentral();
        if (isCentral && !request.central()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG51");
        }
        if (!isCentral && request.central()) {
            requireNoOtherCentral(storeId);
        }
        String name = request.storeName().strip();
        if (stores.existsByStoreNameIgnoreCaseAndIdNot(name, storeId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG49", name);
        }
        // Store states: PLANNED -> ACTIVE or INACTIVE; ACTIVE -> INACTIVE; INACTIVE -> PLANNED (PM 02/11).
        // A closed store reopens by going back to planned, so it passes the opening checks again.
        if (request.status() == StoreStatus.PLANNED && store.getStatus() == StoreStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG68");
        }
        if (request.status() == StoreStatus.ACTIVE && store.getStatus() == StoreStatus.INACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG83");
        }
        if (request.status() == StoreStatus.INACTIVE) {
            requireClosable(store, request.central());
        }
        if (store.getStatus() == StoreStatus.PLANNED && request.status() == StoreStatus.ACTIVE) {
            requireReadyToOpen(store);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String phone = blankToNull(request.phone());
        store.update(name, request.address().strip(), phone, request.status(), now);
        if (request.central() && !isCentral) {
            store.makeCentral(now);
        }
        auditLog.save(AuditLog.record("STORE", storeId, "STORE_UPDATED", actor.id(), null, now));

        if (request.status() == StoreStatus.INACTIVE && store.getOwnerManagerId() != null) {
            store.assignOwnerManager(null, now); // A closed store has no manager.
            auditLog.save(AuditLog.record("STORE", storeId, "STORE_OWNER_MANAGER_REMOVED", actor.id(),
                    "Đóng cửa hàng", now));
        }
        return StoreResponse.from(store, managersOf(store), productsInStockByStore().getOrDefault(storeId, 0L));
    }

    /**
     * A planned store opens only with a manager (an active Branch Manager, or the Owner), parts set up to sell
     * and a retail price in effect for each of them (review 31/10). Each missing condition has its own message.
     * shortcut: parts are set up for a store only by copying another store (UC-05) until a setup screen exists.
     */
    private void requireReadyToOpen(Store store) {
        if (managersOf(store).isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG79");
        }
        List<Long> productIds = stockBalances.findActiveProductIdsOf(store.getId());
        if (productIds.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG80");
        }
        Set<Long> priced = new HashSet<>();
        pricingPolicies.findRetailPricesInEffect(productIds, LocalDateTime.now(clock))
                .forEach(p -> priced.add(p.getProductId()));
        long unpriced = productIds.stream().filter(id -> !priced.contains(id)).count();
        if (unpriced > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG81", unpriced);
        }
    }

    /** The active Owner managing the store first, then its active Branch Managers. */
    private List<UserAccount> managersOf(Store store) {
        UserAccount owner = store.getOwnerManagerId() == null ? null : users.findById(store.getOwnerManagerId())
                .filter(u -> u.getStatus() == AccountStatus.ACTIVE).orElse(null);
        return withOwner(owner,
                users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, store.getId(), AccountStatus.ACTIVE));
    }

    private static List<UserAccount> withOwner(UserAccount owner, List<UserAccount> branchManagers) {
        return owner == null ? branchManagers : Stream.concat(Stream.of(owner), branchManagers.stream()).toList();
    }

    private static int rank(Role role) {
        return switch (role) {
            case OWNER -> 0;
            case BRANCH_MANAGER -> 1;
            default -> 2;
        };
    }

    /**
     * A store closes only when nothing is left behind (soft delete; stores are never removed).
     * shortcut: documents still open at the store (orders, transfers, receipts) are not checked yet; add them with those modules.
     */
    private void requireClosable(Store store, boolean central) {
        if (central) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG65");
        }
        if (store.getStatus() == StoreStatus.INACTIVE) {
            return; // Already closed: only the name, address or phone change.
        }
        long held = stockBalances.countProductsHeldBy(store.getId());
        if (held > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG66", held);
        }
        long accounts = users.countByStoreIdAndStatus(store.getId(), AccountStatus.ACTIVE);
        if (accounts > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG67", accounts);
        }
    }

    // Parallel requests that both pass this check are stopped by the unique index ux_store_central.
    private void requireNoOtherCentral(Long storeId) {
        stores.findByIsCentralTrue()
                .filter(central -> !central.getId().equals(storeId))
                .ifPresent(central -> {
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG50", central.getStoreName());
                });
    }

    private Store saveNew(Store store) {
        try {
            return stores.saveAndFlush(store);
        } catch (DataIntegrityViolationException e) {
            // Another user created the same name a moment earlier.
            throw new BusinessException(HttpStatus.CONFLICT, "MSG49", store.getStoreName());
        }
    }

    private Map<Long, Long> productsInStockByStore() {
        return stockBalances.countProductsInStockByStore().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
