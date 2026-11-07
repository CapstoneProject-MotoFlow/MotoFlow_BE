package fu.hl.g64.motorflow.stockcount.service.impl;

import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
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
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
    public List<StoreOptionResponse> getRequestableStores(AuthenticatedUser user) {
        throw new UnsupportedOperationException("UC-105");
    }
    // endregion

    // region getRequests (owner: X)
    @Override
    public List<CountRequestResponse> getRequests(AuthenticatedUser user, Long storeId,
                                                  StockCountRequestStatus status) {
        throw new UnsupportedOperationException("UC-105");
    }
    // endregion

    // region create (owner: X)
    @Override
    public CountRequestResponse create(AuthenticatedUser user, CountRequestCreateRequest request) {
        throw new UnsupportedOperationException("UC-105");
    }
    // endregion

    // region cancel (owner: X)
    @Override
    public CountRequestResponse cancel(AuthenticatedUser user, long id) {
        throw new UnsupportedOperationException("UC-105");
    }
    // endregion
}
