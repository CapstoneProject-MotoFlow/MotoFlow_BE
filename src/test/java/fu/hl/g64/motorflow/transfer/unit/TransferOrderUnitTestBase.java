package fu.hl.g64.motorflow.transfer.unit;

import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.inventory.service.InventoryService;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.helper.RequestedParts;
import fu.hl.g64.motorflow.transfer.helper.TransferExcessAcceptance;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestLineRepository;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.service.ReplenishmentRequestService;
import fu.hl.g64.motorflow.transfer.service.impl.TransferOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.CLOCK;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.product;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.store;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TransferOrderServiceImpl with every repository and service mocked. The helpers holding the rules under test
 * (StoreScope, RequestedParts) are real objects over the mocked repositories.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class TransferOrderUnitTestBase {

    static final long P1 = 101, P2 = 102, P3 = 103;

    @Mock TransferOrderRepository orders;
    @Mock TransferOrderLineRepository lines;
    @Mock ReplenishmentRequestRepository requests;
    @Mock ReplenishmentRequestLineRepository requestLines;
    @Mock DiscrepancyCaseRepository cases;
    @Mock StoreRepository stores;
    @Mock TransferReconciliation reconciliation;
    @Mock TransferExcessAcceptance excessAcceptance;
    @Mock ProductRepository products;
    @Mock InventoryService inventory;
    @Mock UserAccountRepository users;
    @Mock AuditLogRepository auditLog;
    @Mock NotificationService notifications;
    @Mock InventoryPostingService posting;
    @Mock WarehouseTaskRepository tasks;
    @Mock WarehouseTaskService warehouseTasks;
    @Mock PickingGuide guide;
    @Mock AttachmentService attachments;
    @Mock ReplenishmentRequestService replenishments;

    TransferOrderServiceImpl service;

    /** A row of a "quantity per part" query. */
    static TransferOrderLineRepository.ProductQty qty(long productId, long quantity) {
        return new TransferOrderLineRepository.ProductQty() {
            @Override
            public long getProductId() {
                return productId;
            }

            @Override
            public long getQty() {
                return quantity;
            }
        };
    }

    /** Store 1 is the central warehouse; 2, 3 ACTIVE; 4 INACTIVE; 5 PLANNED. */
    final Map<Long, Store> allStores = new HashMap<>();
    final Map<Long, Product> parts = new LinkedHashMap<>();
    /** Reconciliation figures per part the mocked TransferReconciliation returns; all zero when absent. */
    final Map<Long, TransferReconciliation.Part> figures = new HashMap<>();

    @BeforeEach
    void baseData() {
        service = new TransferOrderServiceImpl(orders, lines, requests, requestLines, cases, stores,
                new StoreScope(stores), new RequestedParts(products), reconciliation, products, inventory, users,
                auditLog, notifications, posting, tasks, warehouseTasks, guide, attachments,
                mock(StockBalanceRepository.class), mock(DiscrepancyResolutionEventRepository.class), excessAcceptance,
                replenishments, CLOCK);
        allStores.put(1L, store(1, StoreStatus.ACTIVE, true));
        allStores.put(2L, store(2, StoreStatus.ACTIVE, false));
        allStores.put(3L, store(3, StoreStatus.ACTIVE, false));
        allStores.put(4L, store(4, StoreStatus.INACTIVE, false));
        allStores.put(5L, store(5, StoreStatus.PLANNED, false));
        parts.put(P1, product(P1, "P1", ProductStatus.ACTIVE));
        parts.put(P2, product(P2, "P2", ProductStatus.ACTIVE));
        parts.put(P3, product(P3, "P3", ProductStatus.ACTIVE));

        when(stores.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(allStores.get(inv.<Long>getArgument(0))));
        when(stores.findAllById(any())).thenAnswer(inv -> {
            List<Store> found = new ArrayList<>();
            inv.<Iterable<Long>>getArgument(0).forEach(id -> Optional.ofNullable(allStores.get(id)).ifPresent(found::add));
            return found;
        });
        when(products.findAllById(any())).thenAnswer(inv -> {
            List<Product> found = new ArrayList<>();
            for (Long id : inv.<Iterable<Long>>getArgument(0)) {
                Product part = parts.get(id);
                if (part != null && !found.contains(part)) {
                    found.add(part);
                }
            }
            return found;
        });
        when(reconciliation.byProduct(anyLong(), any())).thenAnswer(inv -> {
            Map<Long, TransferReconciliation.Part> result = new HashMap<>();
            for (TransferOrderLine line : inv.<List<TransferOrderLine>>getArgument(1)) {
                result.put(line.getProductId(), figures.getOrDefault(line.getProductId(),
                        new TransferReconciliation.Part(0, 0, 0, 0, 0, 0)));
            }
            return result;
        });
        when(users.findAllById(any())).thenReturn(List.of());
        when(orders.findCases(anyLong())).thenReturn(List.of());
        when(orders.findDispatchedLots(anyLong())).thenReturn(List.of());
        when(orders.findReceipts(anyLong())).thenReturn(List.of());
        when(attachments.list(any(), anyLong())).thenReturn(List.of());
        when(auditLog.save(any())).thenAnswer(inv -> inv.<AuditLog>getArgument(0));
    }
}
