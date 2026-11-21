package fu.hl.g64.motorflow.receiving.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.entity.enums.SupplierStatus;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderLineRequest;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrderLine;
import fu.hl.g64.motorflow.receiving.helper.PurchaseOrderReceiving;
import fu.hl.g64.motorflow.receiving.helper.ReceivingLedger;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderLineRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.receiving.service.impl.PurchaseOrderServiceImpl;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.service.ReplenishmentRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.CLOCK;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.product;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.store;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Report 5.1, PurchaseOrderServiceImpl: the data of the Precondition rows. Supplier ID = 3 and warehouse ID = 2 are
 * ACTIVE; parts 10, 11, 13 ACTIVE, 12 DISCONTINUED, 99 unknown; a new PO is saved as ID = 30. The repositories and
 * services the class uses are Mockito objects holding that data.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class PurchaseOrderUnitTestBase {

    static final long PAD = 10, OIL = 11, OLD = 12, LAMP = 13, UNKNOWN = 99;

    @Mock PurchaseOrderRepository orders;
    @Mock PurchaseOrderLineRepository orderLines;
    @Mock GoodsReceiptNoteRepository receipts;
    @Mock GoodsReceiptNoteLineRepository receiptLines;
    @Mock DiscrepancyCaseRepository discrepancies;
    @Mock SupplierRepository suppliers;
    @Mock StoreRepository stores;
    @Mock ProductRepository products;
    @Mock UserAccountRepository users;
    @Mock AuditLogRepository auditLog;
    @Mock NotificationService notifications;
    @Mock ReceivingLedger ledger;
    @Mock DiscrepancySettlement settlement;
    @Mock ReplenishmentRequestService replenishments;
    @Mock WarehouseTaskService warehouseTasks;
    @Mock PurchaseOrderReceiving purchaseOrderReceiving;

    PurchaseOrderServiceImpl service;
    final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    Supplier supplier;
    Store warehouse;
    final Map<Long, Product> parts = new LinkedHashMap<>();
    /** The lines of PO 30 as the line repository holds them. */
    final List<PurchaseOrderLine> savedLines = new ArrayList<>();

    @BeforeEach
    void baseData() {
        service = new PurchaseOrderServiceImpl(orders, orderLines, receipts, receiptLines, discrepancies, suppliers,
                stores, products, users, auditLog, notifications, ledger, settlement, replenishments, warehouseTasks,
                purchaseOrderReceiving, CLOCK);
        supplier = withId(Supplier.create("NCC-03", "Minh Phát", null, null, null, null, null, NOW), 3);
        warehouse = with(store(2, StoreStatus.ACTIVE, false), "storeName", "Kho Gia Lâm");
        parts.put(PAD, product(PAD, "MA-PHANH-01", ProductStatus.ACTIVE));
        parts.put(OIL, product(OIL, "NHOT-01", ProductStatus.ACTIVE));
        parts.put(LAMP, product(LAMP, "DEN-01", ProductStatus.ACTIVE));
        parts.put(OLD, product(OLD, "CU-01", ProductStatus.DISCONTINUED));

        when(suppliers.findById(anyLong())).thenReturn(Optional.empty());
        when(suppliers.findById(3L)).thenAnswer(inv -> Optional.of(supplier));
        when(stores.findById(anyLong())).thenReturn(Optional.empty());
        when(stores.findById(2L)).thenAnswer(inv -> Optional.of(warehouse));
        when(products.findAllById(any())).thenAnswer(inv -> {
            List<Product> found = new ArrayList<>();
            for (Long id : (Iterable<Long>) inv.getArgument(0)) {
                if (parts.containsKey(id)) {
                    found.add(parts.get(id));
                }
            }
            return found;
        });
        when(orders.save(any())).thenAnswer(inv -> withId(inv.getArgument(0, PurchaseOrder.class), 30));
        when(orderLines.saveAll(any())).thenAnswer(inv -> {
            long next = 100 + savedLines.size();
            for (PurchaseOrderLine line : (Iterable<PurchaseOrderLine>) inv.getArgument(0)) {
                savedLines.add(withId(line, next++));
            }
            return List.of();
        });
        when(orderLines.findByPurchaseOrderIdOrderByIdAsc(30L)).thenAnswer(inv -> List.copyOf(savedLines));
        when(receipts.findByPurchaseOrderIdOrderByArrivedAtAscIdAsc(anyLong())).thenReturn(List.of());
        when(ledger.replacementReceiptIds(anyCollection())).thenReturn(java.util.Set.of());
        when(settlement.progress(anyCollection())).thenReturn(Map.of());
        when(auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(any(), anyLong(), any()))
                .thenReturn(List.of());
        when(users.findAllById(any())).thenReturn(List.of());
    }

    static PurchaseOrderLineRequest line(long productId, int quantity, long price) {
        return new PurchaseOrderLineRequest(productId, quantity, price);
    }

    static <T> List<T> listOf(Collection<T> rows) {
        return List.copyOf(rows);
    }
}
