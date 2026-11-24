package fu.hl.g64.motorflow.receiving.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrderLine;
import fu.hl.g64.motorflow.receiving.helper.GoodsReceiptViews;
import fu.hl.g64.motorflow.transfer.service.ReplenishmentRequestService;
import fu.hl.g64.motorflow.receiving.helper.PurchaseOrderReceiving;
import fu.hl.g64.motorflow.receiving.helper.ReceivingLedger;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderLineRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.receiving.service.impl.GoodsReceiptServiceImpl;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.CLOCK;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.product;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.store;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Report 5.1, GoodsReceiptServiceImpl: the data of the Precondition rows. Warehouse ID = 2; PO ID = 30 of supplier
 * ID = 3, lines part 10 x 10 at 60,000 and part 11 x 5 at 70,000. GoodsReceiptViews, which builds the returned
 * GoodsReceiptDetail, is the real class over the same Mockito repositories.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class GoodsReceiptUnitTestBase {

    static final long PAD = 10, OIL = 11;

    @Mock WarehouseTaskRepository tasks;
    @Mock GoodsReceiptNoteRepository receipts;
    @Mock GoodsReceiptNoteLineRepository receiptLines;
    @Mock PurchaseOrderRepository orders;
    @Mock PurchaseOrderLineRepository orderLines;
    @Mock SupplierRepository suppliers;
    @Mock DiscrepancyCaseRepository discrepancies;
    @Mock DiscrepancyResolutionEventRepository events;
    @Mock LotRepository lots;
    @Mock WarehouseTaskService warehouseTasks;
    @Mock InventoryPostingService posting;
    @Mock AttachmentService attachments;
    @Mock NotificationService notifications;
    @Mock AuditLogRepository auditLog;
    @Mock ReceivingLedger ledger;
    @Mock PurchaseOrderReceiving purchaseOrderReceiving;
    @Mock DiscrepancySettlement settlement;
    @Mock StockBalanceRepository stockBalances;
    @Mock ProductRepository products;
    @Mock StoreRepository stores;
    @Mock UserAccountRepository users;
    @Mock ReplenishmentRequestService replenishments;

    GoodsReceiptServiceImpl service;
    final AuthenticatedUser staff = auth(7, Role.STAFF, 2L);
    final AuthenticatedUser manager = auth(5, Role.BRANCH_MANAGER, 2L);
    PurchaseOrder order;
    /** What PO 30 still expects of each part (ReceivingLedger). */
    final Map<Long, Integer> outstanding = new LinkedHashMap<>();
    /** The lines of GRN 50, the cases its confirmation opened and the replacements it brings. */
    final List<GoodsReceiptNoteLine> grnLines = new ArrayList<>();
    final List<DiscrepancyCase> openedCases = new ArrayList<>();
    final List<DiscrepancyResolutionEvent> replacements = new ArrayList<>();
    final Map<Long, Product> parts = new HashMap<>();

    @BeforeEach
    void baseData() {
        GoodsReceiptViews views = new GoodsReceiptViews(orders, receiptLines, discrepancies, events, lots,
                stockBalances, products, suppliers, stores, users, tasks, attachments, ledger, settlement);
        service = new GoodsReceiptServiceImpl(tasks, receipts, receiptLines, orders, orderLines, suppliers,
                discrepancies, events, lots, warehouseTasks, posting, attachments, notifications, auditLog, ledger,
                purchaseOrderReceiving, settlement, views, replenishments, CLOCK);

        order = withId(PurchaseOrder.create(3, 2, null, null, 1, NOW.minusDays(3)), 30);
        outstanding.put(PAD, 10);
        outstanding.put(OIL, 5);
        parts.put(PAD, product(PAD, "MA-PHANH-01", ProductStatus.ACTIVE));
        parts.put(OIL, product(OIL, "NHOT-01", ProductStatus.ACTIVE));

        when(orders.findById(30L)).thenAnswer(inv -> Optional.of(order));
        when(orderLines.findByPurchaseOrderIdOrderByIdAsc(30L)).thenReturn(List.of(
                withId(PurchaseOrderLine.create(30, PAD, 10, 60000), 1), withId(PurchaseOrderLine.create(30, OIL, 5, 70000), 2)));
        when(ledger.of(30L)).thenAnswer(inv -> standing());
        when(suppliers.findById(3L)).thenReturn(Optional.of(withId(Supplier.create("NCC-03", "Minh Phát", null, null,
                null, null, NOW), 3)));
        when(stores.findById(2L)).thenReturn(Optional.of(store(2, StoreStatus.ACTIVE, false)));
        when(products.findAllById(any())).thenAnswer(inv -> {
            List<Product> found = new ArrayList<>();
            ((Iterable<Long>) inv.getArgument(0)).forEach(id -> {
                if (parts.containsKey(id)) {
                    found.add(parts.get(id));
                }
            });
            return found;
        });
        when(receipts.save(any())).thenAnswer(inv -> withId(inv.getArgument(0, GoodsReceiptNote.class), 50));
        when(receiptLines.saveAll(any())).thenAnswer(inv -> {
            long next = grnLines.size() + 1;
            for (GoodsReceiptNoteLine line : (Iterable<GoodsReceiptNoteLine>) inv.getArgument(0)) {
                grnLines.add(withId(line, next++));
            }
            return List.of();
        });
        when(receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(50L)).thenAnswer(inv -> List.copyOf(grnLines));
        when(events.saveAll(any())).thenAnswer(inv -> {
            ((Iterable<DiscrepancyResolutionEvent>) inv.getArgument(0)).forEach(replacements::add);
            return List.of();
        });
        when(events.findByGoodsReceiptNoteIdAndResolutionTypeOrderByIdAsc(50L, ResolutionType.REPLACEMENT_RECEIVED))
                .thenAnswer(inv -> List.copyOf(replacements));
        when(discrepancies.saveAll(any())).thenAnswer(inv -> {
            ((Iterable<DiscrepancyCase>) inv.getArgument(0)).forEach(openedCases::add);
            return List.of();
        });
        when(discrepancies.countOpenByReceipt(anyList())).thenAnswer(inv -> openedCases.isEmpty() ? List.of()
                : List.<Object[]>of(new Object[]{50L, (long) openedCases.stream().filter(DiscrepancyCase::isOpen)
                .count()}));
        when(settlement.progress(anyCollection())).thenAnswer(inv -> {
            Map<Long, DiscrepancySettlement.Progress> progress = new HashMap<>();
            ((Collection<Long>) inv.getArgument(0)).forEach(id -> progress.put(id, pending(id)));
            return progress;
        });
        when(purchaseOrderReceiving.of(any())).thenReturn(new PurchaseOrderReceiving.Openness(false, false, null,
                List.of()));
        when(lots.findByGoodsReceiptNoteLineIdIn(anyList())).thenReturn(List.of());
        when(stockBalances.findByStoreIdAndProductIdIn(anyLong(), any())).thenReturn(List.of());
        when(tasks.findOpenInspections(anyList())).thenReturn(List.of());
        when(users.findAllById(any())).thenReturn(List.of());
        when(attachments.list(any(), anyLong())).thenReturn(List.of());
        when(receipts.existsByPurchaseOrderIdAndDeliveryDocumentNoIgnoreCase(eq(30L), any())).thenReturn(false);
    }

    /** The settlement progress of case {@code id}: nothing done yet unless a test says otherwise. */
    DiscrepancySettlement.Progress pending(long id) {
        return DiscrepancySettlement.Progress.NONE;
    }

    ReceivingLedger.Ledger standing() {
        Map<Long, ReceivingLedger.Part> byPart = new LinkedHashMap<>();
        Map<Long, Integer> ordered = Map.of(PAD, 10, OIL, 5);
        outstanding.forEach((id, left) -> byPart.put(id, new ReceivingLedger.Part(ordered.get(id),
                ordered.get(id) - left, ordered.get(id) - left, 0)));
        return new ReceivingLedger.Ledger(byPart, Set.of());
    }
}
