package fu.hl.g64.motorflow.store.service;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.StockBalanceService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.dto.CopiedStoreResponse;
import fu.hl.g64.motorflow.store.dto.CopyStoreRequest;
import fu.hl.g64.motorflow.store.dto.CreateStoreRequest;
import fu.hl.g64.motorflow.store.dto.StoreResponse;
import fu.hl.g64.motorflow.store.dto.UpdateStoreRequest;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** UC-03 View Store List, UC-04 Create Store, UC-05 Copy Store Configuration, UC-06 Update Store (including closing a store). */
@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository stores;
    private final UserAccountRepository users;
    private final StockBalanceRepository stockBalances;
    private final StockBalanceService stockBalanceService;
    private final AuditLogRepository auditLog;
    private final Clock clock;

    /**
     * Every filter is optional. {@code q} matches store id, name, address or Branch Manager name.
     * shortcut: filters in memory, fine for a chain of a few dozen stores; move to a query if stores reach hundreds.
     */
    @Transactional(readOnly = true)
    public List<StoreResponse> listStores(String q, StoreStatus status, Boolean central) {
        Map<Long, List<UserAccount>> managersByStore = users.findByRoleAndStatus(Role.BRANCH_MANAGER, AccountStatus.ACTIVE)
                .stream().collect(Collectors.groupingBy(UserAccount::getStoreId));
        Map<Long, Long> productsByStore = productsInStockByStore();
        String needle = StringUtils.hasText(q) ? q.strip().toLowerCase(Locale.ROOT) : null;

        return stores.findAll(Sort.by("id")).stream()
                .map(store -> StoreResponse.from(store, managersByStore.getOrDefault(store.getId(), List.of()),
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
    public StoreResponse getStore(long storeId) {
        Store store = stores.findById(storeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
        List<UserAccount> managers = users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, storeId, AccountStatus.ACTIVE);
        return StoreResponse.from(store, managers, productsInStockByStore().getOrDefault(storeId, 0L));
    }

    /** A new store is planned and has no stock (BR-04, BR-64). */
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
        Store store = saveNew(Store.create(name, request.address().strip(), phone, request.central(), now));
        auditLog.save(AuditLog.record("STORE", store.getId(), "STORE_CREATED", actor.id(), null, now));
        return StoreResponse.from(store, List.of(), 0);
    }

    /**
     * UC-05: a planned store that stores parts where the template does, with the same low-stock
     * thresholds. Stock, documents and debts are never copied (BR-64), and the copy is never the central warehouse.
     */
    @Transactional
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
        if (request.status() == StoreStatus.PLANNED && store.getStatus() != StoreStatus.PLANNED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG68");
        }
        if (request.status() == StoreStatus.INACTIVE) {
            requireClosable(store, request.central());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String phone = blankToNull(request.phone());
        store.update(name, request.address().strip(), phone, request.status(), now);
        if (request.central() && !isCentral) {
            store.makeCentral(now);
        }
        auditLog.save(AuditLog.record("STORE", storeId, "STORE_UPDATED", actor.id(), null, now));

        List<UserAccount> managers = users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, storeId, AccountStatus.ACTIVE);
        return StoreResponse.from(store, managers, productsInStockByStore().getOrDefault(storeId, 0L));
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

    // Parallel requests that both pass this check are stopped by the unique index ux_store_central (V11).
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
