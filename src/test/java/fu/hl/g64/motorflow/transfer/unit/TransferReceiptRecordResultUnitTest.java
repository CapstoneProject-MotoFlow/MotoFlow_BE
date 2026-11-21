package fu.hl.g64.motorflow.transfer.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResultRequest;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.service.impl.TransferReceiptServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.CLOCK;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.account;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.product;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet TransferReceipt.recordResult: TransferReceiptServiceImpl.recordResult (UC-75), UTCID01-16. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferReceiptRecordResultUnitTest {

    private static final long P1 = 101, P2 = 102;

    @Mock TransferOrderRepository orders;
    @Mock TransferOrderLineRepository orderLines;
    @Mock GoodsReceiptNoteRepository receipts;
    @Mock GoodsReceiptNoteLineRepository receiptLines;
    @Mock DiscrepancyCaseRepository cases;
    @Mock DiscrepancyResolutionEventRepository events;
    @Mock ProductRepository products;
    @Mock StoreRepository stores;
    @Mock UserAccountRepository users;
    @Mock TransferReconciliation reconciliation;
    @Mock InventoryPostingService posting;
    @Mock WarehouseTaskRepository tasks;
    @Mock WarehouseTaskService warehouseTasks;
    @Mock AuditLogRepository auditLog;
    @Mock NotificationService notifications;
    @Mock AttachmentService attachments;

    private TransferReceiptServiceImpl service;

    private final AuthenticatedUser staffS1 = auth(31, Role.STAFF, 2L);
    private final AuthenticatedUser staffS2 = auth(32, Role.STAFF, 2L);

    private TransferOrder order;
    private final Map<Long, GoodsReceiptNote> allReceipts = new HashMap<>();
    private final Map<Long, List<GoodsReceiptNoteLine>> linesOf = new HashMap<>();

    /**
     * Order 1 IN_TRANSIT; receipt 10 (TRANSFER_IN) of order 1 awaits its result, P1 expected 5; Staff S1 holds the
     * receiving task.
     */
    @BeforeEach
    void defaultData() {
        service = new TransferReceiptServiceImpl(orders, orderLines, receipts, receiptLines, cases, events, products,
                stores, new StoreScope(stores), users, reconciliation, posting, tasks, warehouseTasks, auditLog,
                notifications, attachments, CLOCK);
        order = withId(TransferOrder.create(null, 1L, 2L, 1L, NOW), 1);
        order.dispatch(11L, "Anh Ba", null, NOW);
        addReceipt(10, ReceiptType.TRANSFER_IN, 1, P1, 5);

        Map<Long, Product> parts = Map.of(P1, product(P1, "P1", ProductStatus.ACTIVE),
                P2, product(P2, "P2", ProductStatus.ACTIVE));
        when(products.findAllById(any())).thenAnswer(inv -> {
            List<Product> found = new ArrayList<>();
            inv.<Iterable<Long>>getArgument(0).forEach(id -> {
                if (parts.containsKey(id) && !found.contains(parts.get(id))) {
                    found.add(parts.get(id));
                }
            });
            return found;
        });
        when(orders.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        when(orders.findByIdForUpdate(1L)).thenAnswer(inv -> Optional.of(order));
        when(orders.findById(1L)).thenAnswer(inv -> Optional.of(order));
        when(receipts.findByIdForUpdate(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(allReceipts.get(inv.<Long>getArgument(0))));
        when(receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(anyLong()))
                .thenAnswer(inv -> linesOf.getOrDefault(inv.<Long>getArgument(0), List.of()));
        when(orderLines.openReturnQty(anyLong())).thenReturn(List.of());
        when(reconciliation.pendingClaims(anyLong())).thenReturn(Map.of());
        when(auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(users.findById(anyLong())).thenAnswer(inv -> Optional.of(account(inv.getArgument(0), Role.STAFF, 2L)));
        when(attachments.list(any(), anyLong())).thenReturn(List.of());
        // Only S1 holds the receiving task of receipts 10 and 20.
        doThrow(new BusinessException(HttpStatus.FORBIDDEN, "MSG05"))
                .when(warehouseTasks).requireAssignee(eq(staffS2), eq(TaskType.INSPECTION), anyLong());
    }

    private GoodsReceiptNote addReceipt(long id, ReceiptType type, long orderId, long productId, int expected) {
        GoodsReceiptNote receipt = withId(GoodsReceiptNote.transfer(type, orderId, NOW), id);
        allReceipts.put(id, receipt);
        linesOf.put(id, new ArrayList<>(List.of(GoodsReceiptNoteLine.transfer(id, productId, expected))));
        return receipt;
    }

    private static TransferReceiptResultRequest body(String note, long... productPassedDamaged) {
        List<TransferReceiptResultRequest.Line> items = new ArrayList<>();
        for (int i = 0; i < productPassedDamaged.length; i += 3) {
            items.add(new TransferReceiptResultRequest.Line(productPassedDamaged[i], (int) productPassedDamaged[i + 1],
                    0, (int) productPassedDamaged[i + 2]));
        }
        return new TransferReceiptResultRequest(items, note);
    }

    private void assertSubmitted(TransferReceiptResponse result, int passed, int damaged, int excess) {
        assertThat(result.status()).isEqualTo(GoodsReceiptNoteStatus.AWAITING_CONFIRMATION);
        assertThat(result.recordedBy()).isEqualTo(staffS1.id());
        assertThat(result.recordedByName()).isEqualTo("User " + staffS1.id());
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.passedQty()).isEqualTo(passed);
            assertThat(line.damagedQty()).isEqualTo(damaged);
            assertThat(line.excessQty()).isEqualTo(excess);
        });
        verify(warehouseTasks).submitResult(any(), eq(TaskType.INSPECTION), anyLong());
    }

    private void assertNothingSubmitted() {
        verify(warehouseTasks, never()).submitResult(any(), any(), anyLong());
    }

    @Test
    @DisplayName("UTCID01 N: S1 records [{P1, 5, 0}], no note")
    void utcid01() {
        assertSubmitted(service.recordResult(staffS1, 1, 10, body(null, P1, 5, 0)), 5, 0, 0);
    }

    @Test
    @DisplayName("UTCID02 N: [{P1, 3, 2}] with a note")
    void utcid02() {
        assertSubmitted(service.recordResult(staffS1, 1, 10, body("Hong 2 cai", P1, 3, 2)), 3, 2, 0);
    }

    @Test
    @DisplayName("UTCID03 A: [{P1, 3, 2}] without a note -> MSG265")
    void utcid03() {
        assertBusiness(() -> service.recordResult(staffS1, 1, 10, body(null, P1, 3, 2)), HttpStatus.BAD_REQUEST,
                "MSG265");
        assertNothingSubmitted();
    }

    @Test
    @DisplayName("UTCID04 B: [{P1, 0, 5}] all damaged")
    void utcid04() {
        assertSubmitted(service.recordResult(staffS1, 1, 10, body("Hong 2 cai", P1, 0, 5)), 0, 5, 0);
    }

    @Test
    @DisplayName("UTCID05 B: [{P1, 0, 6}] damaged above 5 -> MSG212")
    void utcid05() {
        assertBusiness(() -> service.recordResult(staffS1, 1, 10, body("Hong 2 cai", P1, 0, 6)),
                HttpStatus.BAD_REQUEST, "MSG212");
        assertNothingSubmitted();
    }

    @Test
    @DisplayName("UTCID06 N: [{P1, 6, 0}] received 6, excess 1")
    void utcid06() {
        assertSubmitted(service.recordResult(staffS1, 1, 10, body("Hong 2 cai", P1, 6, 0)), 6, 0, 1);
    }

    @Test
    @DisplayName("UTCID07 B: return receipt 20, P1 returning 5, [{P1, 5, 1}] -> MSG220")
    void utcid07() {
        addReceipt(20, ReceiptType.RETURN_TO_SOURCE, 1, P1, 5);

        assertBusiness(() -> service.recordResult(staffS1, 1, 20, body("Hong 2 cai", P1, 5, 1)),
                HttpStatus.BAD_REQUEST, "MSG220");
        assertNothingSubmitted();
    }

    @Test
    @DisplayName("UTCID08 B: return receipt 20, [{P1, 5, 0}] = all that is returning")
    void utcid08() {
        addReceipt(20, ReceiptType.RETURN_TO_SOURCE, 1, P1, 5);

        assertSubmitted(service.recordResult(staffS1, 1, 20, body(null, P1, 5, 0)), 5, 0, 0);
    }

    @Test
    @DisplayName("UTCID09 A: return decision P1 x2 pending, [{P1, 6, 0}] -> MSG224")
    void utcid09() {
        when(reconciliation.pendingClaims(1L)).thenReturn(Map.of(P1, 2L));

        assertBusiness(() -> service.recordResult(staffS1, 1, 10, body("Hong 2 cai", P1, 6, 0)),
                HttpStatus.BAD_REQUEST, "MSG224");
        assertNothingSubmitted();
    }

    @Test
    @DisplayName("UTCID10 A: P1 on two lines -> MSG166")
    void utcid10() {
        assertBusiness(() -> service.recordResult(staffS1, 1, 10, body(null, P1, 5, 0, P1, 5, 0)),
                HttpStatus.BAD_REQUEST, "MSG166");
        assertNothingSubmitted();
    }

    @Test
    @DisplayName("UTCID11 A: receipt 10 also expects P2, body leaves it out -> MSG167")
    void utcid11() {
        linesOf.get(10L).add(GoodsReceiptNoteLine.transfer(10, P2, 3));

        assertBusiness(() -> service.recordResult(staffS1, 1, 10, body(null, P1, 5, 0)), HttpStatus.BAD_REQUEST,
                "MSG167");
        assertNothingSubmitted();
    }

    @Test
    @DisplayName("UTCID12 A: Staff S2 does not hold the task -> MSG05")
    void utcid12() {
        assertBusiness(() -> service.recordResult(staffS2, 1, 10, body(null, P1, 5, 0)), HttpStatus.FORBIDDEN,
                "MSG05");
        assertNothingSubmitted();
    }

    @Test
    @DisplayName("UTCID13 A: order 1 is CLOSED -> MSG29")
    void utcid13() {
        with(order, "status", TransferOrderStatus.CLOSED);

        assertBusiness(() -> service.recordResult(staffS1, 1, 10, body(null, P1, 5, 0)), HttpStatus.CONFLICT,
                "MSG29");
        assertNothingSubmitted();
    }

    @Test
    @DisplayName("UTCID14 A: receipt 10 result already submitted -> MSG29")
    void utcid14() {
        with(allReceipts.get(10L), "status", GoodsReceiptNoteStatus.AWAITING_CONFIRMATION);

        assertBusiness(() -> service.recordResult(staffS1, 1, 10, body(null, P1, 5, 0)), HttpStatus.CONFLICT,
                "MSG29");
        assertNothingSubmitted();
    }

    @Test
    @DisplayName("UTCID15 A: order 99999 -> MSG175")
    void utcid15() {
        assertBusiness(() -> service.recordResult(staffS1, 99999, 10, body(null, P1, 5, 0)), HttpStatus.NOT_FOUND,
                "MSG175");
        assertNothingSubmitted();
    }

    @Test
    @DisplayName("UTCID16 A: receipt 30 belongs to order 2 -> MSG132")
    void utcid16() {
        addReceipt(30, ReceiptType.TRANSFER_IN, 2, P1, 5);

        assertBusiness(() -> service.recordResult(staffS1, 1, 30, body(null, P1, 5, 0)), HttpStatus.NOT_FOUND,
                "MSG132");
        assertNothingSubmitted();
    }
}
