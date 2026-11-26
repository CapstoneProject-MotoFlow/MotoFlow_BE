package fu.hl.g64.motorflow.sales.unit;

import fu.hl.g64.motorflow.sales.helper.WaitingOrderReadiness;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.partner.entity.Customer;
import fu.hl.g64.motorflow.partner.repository.CustomerRepository;
import fu.hl.g64.motorflow.receivable.helper.TransactionReferences;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.helper.OrderCollections;
import fu.hl.g64.motorflow.sales.helper.OrderDeposits;
import fu.hl.g64.motorflow.sales.helper.OrderPricing;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.sales.repository.SalesOrderLineRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.sales.service.impl.SalesOrderServiceImpl;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Report 5.1, SalesOrderServiceImpl: the data of the Precondition rows. Store ID = 2 "Kho Gia Lâm" ACTIVE; customer
 * ID = 20 "Garage Hùng"; part ID = 100 NHOT-01 and ID = 200 LOC-01 with retail prices 110,000 and 50,000, ID = 300
 * CU-01 DISCONTINUED, ID = 400 MOI-01 without a retail price. The repositories keep what the service saves, so the
 * returned SalesOrderDetail is built from it; a new order is saved as ID = 10, the next as 11, then 12.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class SalesOrderUnitTestBase {

    static final long OIL = 100, FILTER = 200, OLD = 300, NEW_PART = 400;

    @Mock SalesOrderRepository orders;
    @Mock SalesOrderLineRepository orderLines;
    @Mock ProductRepository products;
    @Mock CustomerRepository customers;
    @Mock StoreRepository stores;
    @Mock UserAccountRepository users;
    @Mock AuditLogRepository auditLog;
    @Mock WarehouseTaskRepository tasks;
    @Mock WarehouseTaskService warehouseTasks;
    @Mock NotificationService notifications;
    @Mock OrderPricing pricing;
    @Mock PickingGuide guide;
    @Mock StockBalanceRepository balances;
    @Mock OrderCollections collections;
    @Mock ReceivableRepository receivables;
    @Mock AttachmentService attachments;
    @Mock PaymentRepository payments;
    @Mock PlatformTransactionManager transactionManager;
    @Mock WaitingOrderReadiness readiness;

    SalesOrderServiceImpl service;
    final AuthenticatedUser manager = auth(5, Role.BRANCH_MANAGER, 2L);
    final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    Store gialam;
    final Map<Long, Product> parts = new HashMap<>();
    /** Retail price in effect now of each part that has one. */
    final Map<Long, Long> retailPrices = new HashMap<>();
    /** What store 2 can sell of each part, not held by other orders. */
    final Map<Long, Integer> sellable = new HashMap<>();
    BigDecimal threshold = new BigDecimal("10");
    BigDecimal margin = new BigDecimal("10");
    /** The orders, lines and history rows as the repositories hold them. */
    final Map<Long, SalesOrder> saved = new LinkedHashMap<>();
    final List<SalesOrderLine> lines = new ArrayList<>();
    final List<AuditLog> history = new ArrayList<>();
    private long nextOrder = 10, nextLine = 1, nextLog = 1;

    @BeforeEach
    void baseData() {
        OrderDeposits deposits = new OrderDeposits(orders, auditLog, CLOCK);
        service = new SalesOrderServiceImpl(orders, orderLines, products, customers, stores, users, auditLog, tasks,
                warehouseTasks, notifications, pricing, guide, balances, collections, receivables, attachments,
                deposits, new TransactionReferences(payments, transactionManager), readiness, CLOCK);
        gialam = with(store(2, StoreStatus.ACTIVE, false), "storeName", "Kho Gia Lâm");
        part(OIL, "NHOT-01", ProductStatus.ACTIVE, 110_000L);
        part(FILTER, "LOC-01", ProductStatus.ACTIVE, 50_000L);
        part(OLD, "CU-01", ProductStatus.DISCONTINUED, 40_000L);
        part(NEW_PART, "MOI-01", ProductStatus.ACTIVE, null);
        sellable.put(OIL, 5);
        sellable.put(FILTER, 5);
        sellable.put(OLD, 5);
        sellable.put(NEW_PART, 5);

        when(stores.findById(anyLong())).thenReturn(Optional.empty());
        when(stores.findById(2L)).thenAnswer(inv -> Optional.of(gialam));
        when(customers.findById(anyLong())).thenReturn(Optional.empty());
        when(customers.findById(20L)).thenReturn(Optional.of(withId(Customer.create("Garage Hùng", "0912000111",
                "Gia Lâm", null, 1L, NOW.minusMonths(3)), 20)));
        when(products.findAllById(any())).thenAnswer(inv -> {
            List<Product> found = new ArrayList<>();
            inv.<Iterable<Long>>getArgument(0).forEach(id -> {
                if (parts.containsKey(id)) {
                    found.add(parts.get(id));
                }
            });
            return found;
        });
        when(pricing.retailPrices(anyCollection(), any())).thenAnswer(inv -> {
            Map<Long, Long> found = new HashMap<>();
            inv.<Collection<Long>>getArgument(0).forEach(id -> {
                if (retailPrices.containsKey(id)) {
                    found.put(id, retailPrices.get(id));
                }
            });
            return found;
        });
        when(pricing.discountThreshold(any())).thenAnswer(inv -> threshold);
        when(pricing.minimumMargin(any())).thenAnswer(inv -> margin);
        when(guide.parts(anyLong(), anyCollection(), any())).thenAnswer(this::stock);
        when(guide.parts(anyLong(), anyCollection(), any(), any())).thenAnswer(this::stock);
        when(collections.of(anyLong())).thenReturn(new OrderCollections.Money(List.of(), Map.of(), 0, 0, 0, 0, 0, 0));

        when(orders.save(any())).thenAnswer(inv -> {
            SalesOrder order = inv.getArgument(0);
            if (order.getId() == null) {
                withId(order, nextOrder++);
            }
            saved.put(order.getId(), order);
            return order;
        });
        when(orders.findByIdForUpdate(anyLong())).thenAnswer(inv -> Optional.ofNullable(saved.get(
                inv.<Long>getArgument(0))));
        // The deposit columns of the orders saved: what a waiting order gave, as the repository query adds it up.
        when(orders.sumDepositAppliedFrom(anyLong())).thenAnswer(inv -> {
            long id = inv.<Long>getArgument(0);
            return Optional.ofNullable(saved.get(id)).map(SalesOrder::getDepositAppliedAmount).orElse(0L)
                    + saved.values().stream().filter(child -> Objects.equals(child.getSplitFromOrderId(), id)
                            && child.getDepositExpiresOn() == null)
                    .mapToLong(SalesOrder::getDepositAppliedAmount).sum();
        });
        when(orderLines.saveAll(any())).thenAnswer(inv -> {
            List<SalesOrderLine> added = new ArrayList<>();
            inv.<Iterable<SalesOrderLine>>getArgument(0).forEach(line -> {
                if (line.getId() == null) {
                    withId(line, nextLine++);
                }
                lines.add(line);
                added.add(line);
            });
            return added;
        });
        when(orderLines.findBySalesOrderIdOrderByLineNoAsc(anyLong())).thenAnswer(inv -> lines.stream()
                .filter(line -> line.getSalesOrderId() == inv.<Long>getArgument(0))
                .sorted(Comparator.comparing(SalesOrderLine::getLineNo)).toList());
        doAnswer(inv -> lines.remove(inv.<SalesOrderLine>getArgument(0))).when(orderLines).delete(any());
        when(auditLog.save(any())).thenAnswer(inv -> {
            AuditLog step = withId(inv.<AuditLog>getArgument(0), nextLog++);
            history.add(step);
            return step;
        });
        when(auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(anyString(), anyLong(),
                anyList())).thenAnswer(inv -> history.stream()
                .filter(step -> step.getEntityType().equals(inv.getArgument(0))
                        && step.getEntityId().equals(inv.getArgument(1))
                        && inv.<List<String>>getArgument(2).contains(step.getAction()))
                .sorted(Comparator.comparing(AuditLog::getOccurredAt).thenComparing(AuditLog::getId).reversed())
                .toList());
        when(tasks.findHistory(any(), anyLong())).thenReturn(List.of());
        when(receivables.findWithRemainingBySalesOrderId(anyLong())).thenReturn(List.of());
        when(users.findAllById(any())).thenReturn(List.of());
    }

    private void part(long id, String sku, ProductStatus status, Long retailPrice) {
        parts.put(id, product(id, sku, status));
        if (retailPrice != null) {
            retailPrices.put(id, retailPrice);
        }
    }

    private Map<Long, PickingGuide.Part> stock(InvocationOnMock inv) {
        Map<Long, PickingGuide.Part> found = new HashMap<>();
        inv.<Collection<Long>>getArgument(1).forEach(id -> found.put(id, new PickingGuide.Part(
                sellable.getOrDefault(id, 0), 0, null, null, List.of(), List.of())));
        return found;
    }

    /** The orders the call saved besides {@code id}. */
    List<SalesOrder> others(long id) {
        return saved.values().stream().filter(order -> order.getId() != id).toList();
    }

    List<SalesOrderLine> linesOf(long orderId) {
        return lines.stream().filter(line -> line.getSalesOrderId() == orderId).toList();
    }
}
