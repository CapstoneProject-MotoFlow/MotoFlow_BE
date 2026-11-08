package fu.hl.g64.motorflow.stockcount.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequest;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequestProduct;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.inventory.helper.InventoryPeriods;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRequestProductRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRequestRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestCreateRequest;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestResponse;
import fu.hl.g64.motorflow.stockcount.dto.StoreOptionResponse;
import fu.hl.g64.motorflow.stockcount.helper.StockCountAccess;
import fu.hl.g64.motorflow.stockcount.helper.StockCountMapper;
import fu.hl.g64.motorflow.stockcount.service.StockCountRequestService;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;

/** UC-105. The whole class belongs to worker X. */
@Service
@RequiredArgsConstructor
public class StockCountRequestServiceImpl implements StockCountRequestService {

    private final StockCountRequestRepository requests;
    private final StockCountRequestProductRepository requestProducts;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final StockBalanceRepository stockBalances;
    private final InventoryPeriods inventoryPeriods;
    private final NotificationService notifications;
    private final StockCountAccess access;
    private final StockCountMapper mapper;
    private final Clock clock;

    // region getRequestableStores (owner: X)
    @Override
    @Transactional(readOnly = true)
    public List<StoreOptionResponse> getRequestableStores(AuthenticatedUser user) {
        access.requireOwner(user);
        return stores.findByStatusOrderByStoreNameAsc(StoreStatus.ACTIVE).stream()
                .map(store -> new StoreOptionResponse(store.getId(), store.getStoreName())).toList();
    }
    // endregion

    // region getRequests (owner: X)
    @Override
    @Transactional(readOnly = true)
    public List<CountRequestResponse> getRequests(AuthenticatedUser user, Long storeId,
                                                  StockCountRequestStatus status) {
        Long scope = storeId;
        if (user.role() != Role.OWNER) {
            scope = user.storeId();
            access.requireManagerOf(user, storeId != null ? storeId : scope == null ? -1 : scope);
        }
        return mapper.toRequests(requests.search(scope, status));
    }
    // endregion

    // region create (owner: X)
    @Override
    @Transactional
    public CountRequestResponse create(AuthenticatedUser user, CountRequestCreateRequest request) {
        access.requireOwner(user);
        Store store = stores.findById(request.storeId()).filter(s -> s.getStatus() == StoreStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG242"));
        List<Long> productIds = request.productIds() == null ? List.of() : request.productIds();
        if (products.findAllById(productIds).size() != new HashSet<>(productIds).size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG242");
        }
        LocalDate today = LocalDate.now(clock);
        inventoryPeriods.openIfMissing(today);
        access.requireOpenPeriod(inventoryPeriods.findFor(today).orElseThrow().getId());

        String note = request.scopeNote() == null || request.scopeNote().isBlank() ? null : request.scopeNote().strip();
        boolean valid = switch (request.scopeType()) {
            case WHOLE -> productIds.isEmpty() && note == null;
            case PRODUCTS -> !productIds.isEmpty() && new HashSet<>(productIds).size() == productIds.size()
                    && note == null;
            case AREA -> productIds.isEmpty() && note != null
                    && stockBalances.findStorageAreas(store.getId()).contains(note);
        };
        if (!valid) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }

        StockCountRequest saved = requests.save(StockCountRequest.create(store.getId(), request.scopeType(), note,
                request.reason().strip(), user.id(), LocalDateTime.now(clock)));
        productIds.forEach(id -> requestProducts.save(StockCountRequestProduct.create(saved.getId(), id)));
        notifications.notifyStoreManagers(store.getId(), "STOCK_COUNT_REQUESTED",
                "Owner yêu cầu cửa hàng %s kiểm kê kho (yêu cầu kiểm kê #%d).".formatted(store.getStoreName(),
                        saved.getId()), "STOCK_COUNT_REQUEST", saved.getId());
        return mapper.toRequest(saved);
    }
    // endregion

    // region cancel (owner: X)
    @Override
    @Transactional
    public CountRequestResponse cancel(AuthenticatedUser user, long id) {
        StockCountRequest request = access.lockRequest(id);
        access.requireOwner(user);
        if (!request.isPending()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG233");
        }
        request.cancel(LocalDateTime.now(clock));
        notifications.notifyStoreManagers(request.getStoreId(), "STOCK_COUNT_REQUEST_CANCELLED",
                "Owner đã hủy yêu cầu kiểm kê #%d của cửa hàng %s.".formatted(id,
                        stores.findById(request.getStoreId()).orElseThrow().getStoreName()),
                "STOCK_COUNT_REQUEST", id);
        return mapper.toRequest(request);
    }
    // endregion
}
