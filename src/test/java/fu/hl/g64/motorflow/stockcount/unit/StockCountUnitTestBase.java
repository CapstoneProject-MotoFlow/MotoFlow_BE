package fu.hl.g64.motorflow.stockcount.unit;

import fu.hl.g64.motorflow.catalog.entity.PartCategory;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.PartCategoryStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import fu.hl.g64.motorflow.catalog.repository.PartCategoryRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequest;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequestProduct;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.helper.InventoryPeriods;
import fu.hl.g64.motorflow.inventory.repository.InventoryPeriodRepository;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountLineRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRequestProductRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRequestRepository;
import fu.hl.g64.motorflow.inventory.repository.StockMovementRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;
import fu.hl.g64.motorflow.stockcount.helper.OpeningBalances;
import fu.hl.g64.motorflow.stockcount.helper.StockCountAccess;
import fu.hl.g64.motorflow.stockcount.helper.StockCountMapper;
import fu.hl.g64.motorflow.stockcount.service.CountSheetService;
import fu.hl.g64.motorflow.stockcount.service.impl.StockCountRequestServiceImpl;
import fu.hl.g64.motorflow.stockcount.service.impl.StockCountServiceImpl;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.CLOCK;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.product;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.store;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StockCountServiceImpl and StockCountRequestServiceImpl with every repository and service mocked. The helpers holding
 * the rules under test (StockCountAccess, InventoryPeriods, OpeningBalances) are real objects over the mocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class StockCountUnitTestBase {

    static final long P1 = 101, P2 = 102, P3 = 103;
    static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1), OCTOBER = LocalDate.of(2026, 10, 1);

    @Mock StockCountRepository counts;
    @Mock StockCountLineRepository lines;
    @Mock StockCountRequestRepository requests;
    @Mock StockCountRequestProductRepository requestProducts;
    @Mock LotBalanceRepository lotBalances;
    @Mock StockBalanceRepository stockBalances;
    @Mock ProductRepository products;
    @Mock PartCategoryRepository categories;
    @Mock StoreRepository stores;
    @Mock WarehouseTaskRepository tasks;
    @Mock InventoryPeriodRepository periods;
    @Mock InventoryPostingService posting;
    @Mock StockMovementRepository movements;
    @Mock LotRepository lots;
    @Mock WarehouseTaskService warehouseTasks;
    @Mock NotificationService notifications;
    @Mock CountSheetService countSheets;
    @Mock StockCountMapper mapper;

    StockCountServiceImpl service;
    StockCountRequestServiceImpl requestService;
    final StockCountDetailResponse detail = mock(StockCountDetailResponse.class);

    /** Period 11 = 11/2026 (OPEN), period 10 = 10/2026 (CLOSED). */
    final Map<Long, InventoryPeriodStatus> periodStatus = new HashMap<>();
    final Map<Long, Product> parts = new HashMap<>();
    final List<StockCountLine> countLines = new ArrayList<>();

    @BeforeEach
    void baseData() {
        InventoryPeriods inventoryPeriods = new InventoryPeriods(periods);
        StockCountAccess access = new StockCountAccess(counts, requests, tasks, periods);
        OpeningBalances openingBalances = new OpeningBalances(movements, periods, inventoryPeriods, lines, products,
                lots, posting);
        service = new StockCountServiceImpl(counts, lines, requests, requestProducts, lotBalances, stockBalances,
                products, categories, stores, tasks, inventoryPeriods, posting, openingBalances, warehouseTasks,
                notifications, countSheets, access, mapper, CLOCK);
        requestService = new StockCountRequestServiceImpl(requests, requestProducts, stores, products, categories,
                stockBalances, inventoryPeriods, notifications, access, mapper, CLOCK);

        periodStatus.put(11L, InventoryPeriodStatus.OPEN);
        periodStatus.put(10L, InventoryPeriodStatus.CLOSED);
        when(periods.findByPeriodMonth(any())).thenReturn(Optional.empty());
        InventoryPeriod november = period(11), october = period(10);
        when(periods.findByPeriodMonth(NOVEMBER)).thenReturn(Optional.of(november));
        when(periods.findByPeriodMonth(OCTOBER)).thenReturn(Optional.of(october));
        when(periods.findById(anyLong())).thenReturn(Optional.empty());
        when(periods.findById(11L)).thenReturn(Optional.of(november));
        when(periods.findById(10L)).thenReturn(Optional.of(october));

        // Store 2 has a storage area "Ke A"; store 3 ACTIVE; store 4 INACTIVE.
        when(stores.findById(anyLong())).thenReturn(Optional.empty());
        when(stores.findById(2L)).thenReturn(Optional.of(store(2, StoreStatus.ACTIVE, false)));
        when(stores.findById(3L)).thenReturn(Optional.of(store(3, StoreStatus.ACTIVE, false)));
        when(stores.findById(4L)).thenReturn(Optional.of(store(4, StoreStatus.INACTIVE, false)));
        when(stores.findForUpdateById(2L)).thenReturn(Optional.of(store(2, StoreStatus.ACTIVE, false)));
        when(stockBalances.findStorageAreas(2L)).thenReturn(List.of("Ke A"));

        parts.put(P1, product(P1, "P1", ProductStatus.ACTIVE));
        parts.put(P2, product(P2, "P2", ProductStatus.ACTIVE));
        parts.put(P3, product(P3, "P3", ProductStatus.ACTIVE));
        when(products.findAllById(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().distinct().filter(parts::containsKey).map(parts::get).toList();
        });

        // Category 3 and 4 ACTIVE, 9 INACTIVE.
        when(categories.findById(anyLong())).thenReturn(Optional.empty());
        when(categories.findById(3L)).thenReturn(Optional.of(category(3, PartCategoryStatus.ACTIVE)));
        when(categories.findById(4L)).thenReturn(Optional.of(category(4, PartCategoryStatus.ACTIVE)));
        when(categories.findById(9L)).thenReturn(Optional.of(category(9, PartCategoryStatus.INACTIVE)));

        when(counts.findById(anyLong())).thenReturn(Optional.empty());
        when(counts.findForUpdate(anyLong())).thenReturn(Optional.empty());
        when(requests.findById(anyLong())).thenReturn(Optional.empty());
        when(requests.findForUpdate(anyLong())).thenReturn(Optional.empty());
        when(lines.findByStockCountIdOrderByProductIdAscExpiryDateAscIdAsc(1L)).thenAnswer(inv -> countLines);
        when(countSheets.needsApproval(any())).thenAnswer(inv -> {
            List<StockCountLine> kept = inv.getArgument(0);
            return kept.stream().anyMatch(StockCountLine::needsApproval);
        });
        when(mapper.toDetail(any(), any())).thenReturn(detail);
    }

    private InventoryPeriod period(long id) {
        InventoryPeriod period = mock(InventoryPeriod.class);
        when(period.getId()).thenReturn(id);
        when(period.isOpen()).thenAnswer(inv -> periodStatus.get(id) == InventoryPeriodStatus.OPEN);
        when(period.getPeriodMonth()).thenReturn(id == 11 ? NOVEMBER : OCTOBER);
        return period;
    }

    private static PartCategory category(long id, PartCategoryStatus status) {
        PartCategory category = withId(PartCategory.create("Nhom " + id, VehicleType.MOTORCYCLE, null, null, NOW), id);
        return with(category, "status", status);
    }

    /** A stock count request of {@code storeId}, PENDING unless changed. */
    StockCountRequest countRequest(long id, long storeId, ScopeType scope, List<Long> productIds) {
        StockCountRequest request = withId(StockCountRequest.create(storeId, scope, null, null, "Kiem cuoi quy", 1L,
                NOW), id);
        when(requests.findById(id)).thenReturn(Optional.of(request));
        when(requests.findForUpdate(id)).thenReturn(Optional.of(request));
        when(requestProducts.findByStockCountRequestIdOrderByProductId(id)).thenReturn(productIds.stream()
                .map(productId -> StockCountRequestProduct.create(id, productId)).toList());
        return request;
    }

    /** Stock count 1 of store 2, locked and loaded by id. */
    StockCount registerCount(StockCount count) {
        withId(count, 1);
        when(counts.findById(1L)).thenReturn(Optional.of(count));
        when(counts.findForUpdate(1L)).thenReturn(Optional.of(count));
        return count;
    }

    /** A count line of count 1 with its book quantity taken. */
    StockCountLine line(long id, long productId, Long lotId, int book, int bookDamaged) {
        StockCountLine line = withId(StockCountLine.create(1L, productId, lotId, null), id);
        line.takeBookQty(book, bookDamaged);
        countLines.add(line);
        return line;
    }

    /** A row of {@link LotBalanceRepository#findCountedLots}. */
    static LotBalanceRepository.CountedLot countedLot(long productId, long lotId, int available, int damaged) {
        return new LotBalanceRepository.CountedLot() {
            @Override
            public Long getProductId() {
                return productId;
            }

            @Override
            public Long getLotId() {
                return lotId;
            }

            @Override
            public LocalDate getExpiryDate() {
                return null;
            }

            @Override
            public LocalDate getReceivedDate() {
                return NOW.toLocalDate();
            }

            @Override
            public int getAvailable() {
                return available;
            }

            @Override
            public int getDamaged() {
                return damaged;
            }
        };
    }
}
