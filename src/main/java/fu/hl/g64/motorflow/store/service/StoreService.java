package fu.hl.g64.motorflow.store.service;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.dto.CreateStoreRequest;
import fu.hl.g64.motorflow.store.dto.StoreResponse;
import fu.hl.g64.motorflow.store.dto.UpdateStoreRequest;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.Warehouse;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.store.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** UC-03 View Store List, UC-04 Create Store, UC-06 Update Store (including closing a store). */
@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository stores;
    private final WarehouseRepository warehouses;
    private final UserAccountRepository users;
    private final StockBalanceRepository stockBalances;
    private final AuditLogRepository auditLog;
    private final Clock clock;

    /**
     * Every filter is optional. {@code q} matches store id, name, address or Branch Manager name.
     * shortcut: filters in memory, fine for a chain of a few dozen stores; move to a query if stores reach hundreds.
     */
    @Transactional(readOnly = true)
    public List<StoreResponse> listStores(String q, StoreStatus status, Boolean central) {
        Map<Long, Warehouse> warehouseByStore = warehouses.findAll().stream()
                .collect(Collectors.toMap(Warehouse::getStoreId, Function.identity()));
        Map<Long, List<UserAccount>> managersByStore = users.findByRoleAndStatus(Role.BRANCH_MANAGER, AccountStatus.ACTIVE)
                .stream().collect(Collectors.groupingBy(UserAccount::getStoreId));
        Map<Long, Long> productsByWarehouse = productsInStockByWarehouse();
        String needle = StringUtils.hasText(q) ? q.strip().toLowerCase(Locale.ROOT) : null;

        return stores.findAll(Sort.by("id")).stream()
                .map(store -> {
                    Warehouse warehouse = warehouseByStore.get(store.getId());
                    return StoreResponse.from(store, warehouse, managersByStore.getOrDefault(store.getId(), List.of()),
                            warehouse == null ? 0 : productsByWarehouse.getOrDefault(warehouse.getId(), 0L));
                })
                .filter(s -> status == null || s.status() == status)
                .filter(s -> central == null || (s.warehouse() != null && s.warehouse().central() == central))
                .filter(s -> needle == null || Stream.concat(
                                Stream.of(s.storeName(), String.valueOf(s.id()), s.address()),
                                s.managers().stream().map(StoreResponse.ManagerInfo::fullName))
                        .anyMatch(text -> text != null && text.toLowerCase(Locale.ROOT).contains(needle)))
                .toList();
    }

    @Transactional(readOnly = true)
    public StoreResponse getStore(long storeId) {
        Store store = stores.findById(storeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
        Warehouse warehouse = warehouses.findByStoreId(storeId).orElse(null);
        List<UserAccount> managers = users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, storeId, AccountStatus.ACTIVE);
        return StoreResponse.from(store, warehouse, managers,
                warehouse == null ? 0 : productsInStockByWarehouse().getOrDefault(warehouse.getId(), 0L));
    }

    /** A new store is planned, has exactly one warehouse and no stock (BR-04, BR-64). */
    @Transactional
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
        Store store = Store.create(name, request.address().strip(), phone, now);
        try {
            store = stores.saveAndFlush(store);
        } catch (DataIntegrityViolationException e) {
            // Another user created the same name a moment earlier.
            throw new BusinessException(HttpStatus.CONFLICT, "MSG49", name);
        }
        Warehouse warehouse = warehouses.save(Warehouse.create(store.getId(), request.warehouseName().strip(),
                store.getAddress(), phone, request.central(), now));
        auditLog.save(AuditLog.record("STORE", store.getId(), "STORE_CREATED", actor.id(), null, now));
        return StoreResponse.from(store, warehouse, List.of(), 0);
    }

    /** The warehouse follows the store's address and phone. */
    @Transactional
    public StoreResponse updateStore(AuthenticatedUser actor, long storeId, UpdateStoreRequest request) {
        Store store = stores.findById(storeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
        Warehouse warehouse = warehouses.findByStoreId(storeId).orElse(null);
        boolean isCentral = warehouse != null && warehouse.isCentral();
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
        if (request.status() == StoreStatus.PLANNED && store.getStatus() != StoreStatus.PLANNED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG68");
        }
        if (request.status() == StoreStatus.INACTIVE) {
            requireClosable(store, warehouse, request.central());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String phone = blankToNull(request.phone());
        store.update(name, request.address().strip(), phone, request.status(), now);
        if (warehouse == null) {
            // Stores inserted before BR-04 was enforced get their warehouse now.
            warehouse = warehouses.save(Warehouse.create(storeId, request.warehouseName().strip(), store.getAddress(),
                    phone, request.central(), now));
        } else {
            warehouse.update(request.warehouseName().strip(), store.getAddress(), phone, now);
            if (request.central() && !isCentral) {
                warehouse.makeCentral(now);
            }
        }
        auditLog.save(AuditLog.record("STORE", storeId, "STORE_UPDATED", actor.id(), null, now));

        List<UserAccount> managers = users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, storeId, AccountStatus.ACTIVE);
        return StoreResponse.from(store, warehouse, managers,
                productsInStockByWarehouse().getOrDefault(warehouse.getId(), 0L));
    }

    /**
     * A store closes only when nothing is left behind (soft delete; stores are never removed).
     * shortcut: documents still open at the store (orders, transfers, receipts) are not checked yet; add them with those modules.
     */
    private void requireClosable(Store store, Warehouse warehouse, boolean central) {
        if (central) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG65");
        }
        if (store.getStatus() == StoreStatus.INACTIVE) {
            return; // Already closed: only the name, address or phone change.
        }
        long held = warehouse == null ? 0 : stockBalances.countProductsHeldBy(warehouse.getId());
        if (held > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG66", held);
        }
        long accounts = users.countByStoreIdAndStatus(store.getId(), AccountStatus.ACTIVE);
        if (accounts > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG67", accounts);
        }
    }

    // Parallel requests that both pass this check are stopped by the unique index ux_warehouse_1 (V6).
    private void requireNoOtherCentral(Long storeId) {
        warehouses.findByIsCentralTrue()
                .filter(central -> !central.getStoreId().equals(storeId))
                .ifPresent(central -> {
                    String code = stores.findById(central.getStoreId()).map(Store::getStoreName).orElse("?");
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG50", code);
                });
    }

    private Map<Long, Long> productsInStockByWarehouse() {
        return stockBalances.countProductsInStockByWarehouse().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
