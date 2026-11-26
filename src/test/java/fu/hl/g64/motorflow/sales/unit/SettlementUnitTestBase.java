package fu.hl.g64.motorflow.sales.unit;

import fu.hl.g64.motorflow.sales.helper.WaitingOrderReadiness;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.partner.repository.CustomerRepository;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.helper.TransactionReferences;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.helper.OrderCollections;
import fu.hl.g64.motorflow.sales.helper.OrderDeposits;
import fu.hl.g64.motorflow.sales.helper.OrderPricing;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.sales.helper.SalesOrderHistory;
import fu.hl.g64.motorflow.sales.repository.SalesOrderLineRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.sales.service.SalesOrderService;
import fu.hl.g64.motorflow.sales.service.impl.SalesOrderSettlementServiceImpl;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

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
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Report 5.1, SalesOrderSettlementServiceImpl: the data of the Precondition rows. Store ID = 2; part ID = 100 NHOT-01
 * (oil) and ID = 200 LOC-01 (filter). Sales order ID = 10 of store 2: CONFIRMED, version 1, retail; oil delivered 2 at
 * 100,000 and filter delivered 1 at 50,000 (total 250,000); nothing collected. Sellable oil 5, filter 5; discount
 * threshold 10%. OrderCollections and TransactionReferences are the real classes over the payments kept here; the
 * transaction template runs the callback directly; a new payment or refund is saved as ID = 700, then 701.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class SettlementUnitTestBase {

    static final long OIL = 100, FILTER = 200;

    @Mock SalesOrderRepository orders;
    @Mock SalesOrderLineRepository orderLines;
    @Mock ProductRepository products;
    @Mock CustomerRepository customers;
    @Mock PaymentRepository payments;
    @Mock ReceivableRepository receivables;
    @Mock AuditLogRepository auditLog;
    @Mock WarehouseTaskRepository tasks;
    @Mock WarehouseTaskService warehouseTasks;
    @Mock NotificationService notifications;
    @Mock AttachmentService attachments;
    @Mock InventoryPostingService inventory;
    @Mock OrderPricing pricing;
    @Mock PickingGuide guide;
    @Mock SalesOrderService salesOrders;
    @Mock TransactionTemplate transactions;
    @Mock MessageSource messages;
    @Mock PlatformTransactionManager transactionManager;
    @Mock WaitingOrderReadiness readiness;

    SalesOrderSettlementServiceImpl service;
    OrderCollections collections;
    final AuthenticatedUser manager = auth(5, Role.BRANCH_MANAGER, 2L);
    final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    final Map<Long, SalesOrder> saved = new LinkedHashMap<>();
    final List<SalesOrderLine> lines = new ArrayList<>();
    final List<Payment> paid = new ArrayList<>();
    final List<AuditLog> history = new ArrayList<>();
    final List<WarehouseTask> openPickings = new ArrayList<>();
    final Map<Long, Integer> sellable = new HashMap<>();
    final Map<Long, Product> parts = new HashMap<>();
    BigDecimal threshold = new BigDecimal("10");
    SalesOrder order;
    SalesOrderLine oil, filter;
    private long nextPayment = 700, nextLog = 1;

    @BeforeEach
    void baseData() {
        collections = new OrderCollections(payments, orders);
        OrderDeposits deposits = new OrderDeposits(orders, auditLog, CLOCK);
        service = new SalesOrderSettlementServiceImpl(orders, orderLines, products, customers, payments,
                new TransactionReferences(payments, transactionManager), receivables, auditLog, tasks,
                warehouseTasks, notifications, attachments, inventory, collections, deposits, pricing, guide,
                salesOrders, transactions, messages, readiness, CLOCK);
        parts.put(OIL, product(OIL, "NHOT-01", ProductStatus.ACTIVE));
        parts.put(FILTER, product(FILTER, "LOC-01", ProductStatus.ACTIVE));
        sellable.put(OIL, 5);
        sellable.put(FILTER, 5);
        order = confirmedOrder(10, BigDecimal.ZERO.setScale(2), 100_000);

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
        when(orders.depositAppliedTo(anyLong())).thenAnswer(inv -> Optional.ofNullable(saved.get(
                inv.<Long>getArgument(0))).map(SalesOrder::getDepositAppliedAmount).orElse(0L));
        when(orderLines.findBySalesOrderIdOrderByLineNoAsc(anyLong())).thenAnswer(inv -> lines.stream()
                .filter(line -> line.getSalesOrderId() == inv.<Long>getArgument(0))
                .sorted(Comparator.comparing(SalesOrderLine::getLineNo)).toList());
        when(products.findAllById(any())).thenAnswer(inv -> {
            List<Product> found = new ArrayList<>();
            inv.<Iterable<Long>>getArgument(0).forEach(id -> {
                if (parts.containsKey(id)) {
                    found.add(parts.get(id));
                }
            });
            return found;
        });
        when(guide.parts(anyLong(), anyCollection(), any(), any())).thenAnswer(inv -> {
            Map<Long, PickingGuide.Part> found = new HashMap<>();
            inv.<Collection<Long>>getArgument(1).forEach(id -> found.put(id, new PickingGuide.Part(
                    sellable.getOrDefault(id, 0), 0, null, null, List.of(), List.of(), List.of())));
            return found;
        });
        when(pricing.discountThreshold(any())).thenAnswer(inv -> threshold);
        when(transactions.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(null));
        when(messages.getMessage(anyString(), any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        when(payments.findBySalesOrderIdOrderByRecordedAtAscIdAsc(anyLong())).thenAnswer(inv -> paid.stream()
                .filter(row -> Objects.equals(row.getSalesOrderId(), inv.getArgument(0))).toList());
        when(payments.sumCorrections(any())).thenReturn(List.of());
        when(payments.findByTransactionReference(anyString())).thenAnswer(inv -> paid.stream()
                .filter(row -> inv.getArgument(0).equals(row.getTransactionReference())
                        && row.getStatus() != PaymentStatus.REJECTED).toList());
        when(payments.saveAndFlush(any())).thenAnswer(inv -> {
            Payment row = withId(inv.<Payment>getArgument(0), nextPayment++);
            paid.add(row);
            return row;
        });
        when(inventory.takeOutAll(anyList())).thenAnswer(inv -> {
            List<List<LotQuantity>> taken = new ArrayList<>();
            long lot = 900;
            for (OutPosting out : inv.<List<OutPosting>>getArgument(0)) {
                taken.add(List.of(new LotQuantity(lot++, out.quantity())));
            }
            return taken;
        });
        when(auditLog.save(any())).thenAnswer(inv -> {
            AuditLog step = withId(inv.<AuditLog>getArgument(0), nextLog++);
            history.add(step);
            return step;
        });
        when(auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(anyString(), anyLong(),
                anyList())).thenAnswer(inv -> history.stream()
                .filter(step -> step.getEntityId().equals(inv.getArgument(1))
                        && inv.<List<String>>getArgument(2).contains(step.getAction())).toList());
        when(tasks.findOpenPickings(anyCollection())).thenAnswer(inv -> List.copyOf(openPickings));
    }

    /** Order {@code id} of store 2, picked oil 2 and filter 1 and confirmed with them; oil at {@code oilPrice}. */
    SalesOrder confirmedOrder(long id, BigDecimal rate, long oilPrice) {
        SalesOrder found = withId(SalesOrder.create(2, new SalesOrder.Customer(null, "Anh Nam", null, null),
                rate.signum() == 0 ? PriceType.RETAIL : PriceType.WHOLESALE, rate, null, 5, NOW.minusHours(2)), id);
        found.picked(NOW.minusHours(1));
        saved.put(id, found);
        oil = withId(SalesOrderLine.create(id, 1, OIL, 2, 100_000, oilPrice), id * 10 + 1);
        oil.recordPicked(2);
        oil.deliver(2);
        filter = withId(SalesOrderLine.create(id, 2, FILTER, 1, 50_000, 50_000), id * 10 + 2);
        filter.recordPicked(1);
        filter.deliver(1);
        lines.addAll(List.of(oil, filter));
        found.confirm(5, NOW.minusMinutes(30));
        return found;
    }

    /** A payment of order 10 recorded before, verified unless said otherwise, with ID = {@code id}. */
    Payment collected(long id, long amount, boolean verified) {
        Payment row = withId(Payment.orderPayment(10, amount, null, PaymentMethod.CASH, null, verified, 5,
                NOW.minusMinutes(20)), id);
        paid.add(row);
        return row;
    }

    Payment refunded(long id, long amount) {
        Payment row = withId(Payment.refund(10, amount, PaymentMethod.CASH, null, true, 5, NOW.minusMinutes(10)), id);
        paid.add(row);
        return row;
    }

    /** Version 2 of order 10 was saved since the seller opened version 1. */
    void versionSavedSince() {
        history.add(withId(AuditLog.record(SalesOrderHistory.ENTITY, 10L, SalesOrderHistory.VERSIONED, 5L, null,
                null, Map.of(), NOW.minusMinutes(5)), 950));
    }

    void status(SalesOrderStatus status) {
        with(order, "status", status);
    }

    /** Order 10 is still a draft, never submitted. */
    void draft() {
        saved.put(10L, withId(SalesOrder.createDraft(2, new SalesOrder.Customer(null, null, null, null),
                PriceType.RETAIL, BigDecimal.ZERO.setScale(2), null, 5, NOW), 10));
    }
}
