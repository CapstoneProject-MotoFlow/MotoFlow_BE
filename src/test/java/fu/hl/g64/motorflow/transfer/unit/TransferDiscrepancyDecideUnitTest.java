package fu.hl.g64.motorflow.transfer.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentRequestRepository;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.dto.TransferDecisionRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferDecisionRequest.Decision;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.service.TransferOrderService;
import fu.hl.g64.motorflow.transfer.service.impl.TransferDiscrepancyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.CLOCK;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.product;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.store;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static fu.hl.g64.motorflow.transfer.unit.TransferOrderUnitTestBase.qty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet TransferDiscrepancy.decide: TransferDiscrepancyServiceImpl.decide (UC-76), UTCID01-14. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferDiscrepancyDecideUnitTest {

    private static final long P1 = 101, P2 = 102;

    @Mock TransferOrderRepository orders;
    @Mock TransferOrderLineRepository lines;
    @Mock TransferReconciliation reconciliation;
    @Mock AdjustmentRequestRepository adjustments;
    @Mock DiscrepancyCaseRepository cases;
    @Mock DiscrepancyResolutionEventRepository events;
    @Mock ProductRepository products;
    @Mock StoreRepository stores;
    @Mock AuditLogRepository auditLog;
    @Mock NotificationService notifications;
    @Mock TransferOrderService transferOrders;

    private TransferDiscrepancyServiceImpl service;

    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    private final AuthenticatedUser centralBm = auth(2, Role.BRANCH_MANAGER, 1L);
    private final AuthenticatedUser bmStore2 = auth(3, Role.BRANCH_MANAGER, 2L);
    private final TransferOrderDetailResponse detail = mock(TransferOrderDetailResponse.class);

    private TransferOrder order;
    private DiscrepancyCase shortageP1;
    private int uncoveredP1;
    private final List<DiscrepancyResolutionEvent> pendingReturns = new ArrayList<>();

    /** Order 1: warehouse 1 -> store 2, RECONCILING; P1 dispatched 5, received 3, open shortage of 2; P2 received. */
    @BeforeEach
    void defaultData() {
        service = new TransferDiscrepancyServiceImpl(orders, lines, reconciliation, adjustments, cases, events,
                products, new StoreScope(stores), auditLog, notifications, transferOrders, CLOCK);
        when(stores.findById(1L)).thenReturn(Optional.of(store(1, StoreStatus.ACTIVE, true)));
        when(stores.findById(2L)).thenReturn(Optional.of(store(2, StoreStatus.ACTIVE, false)));

        order = withId(TransferOrder.create(null, 1L, 2L, 1L, NOW), 1);
        order.dispatch(2L, null, null, NOW);
        with(order, "status", TransferOrderStatus.RECONCILING);
        when(orders.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        when(orders.findByIdForUpdate(1L)).thenAnswer(inv -> Optional.of(order));

        when(products.findById(anyLong())).thenReturn(Optional.empty());
        when(products.findById(P1)).thenReturn(Optional.of(product(P1, "P1", ProductStatus.ACTIVE)));
        when(products.findById(P2)).thenReturn(Optional.of(product(P2, "P2", ProductStatus.ACTIVE)));

        shortageP1 = withId(DiscrepancyCase.transfer(1L, P1, 2, NOW), 11);
        when(orders.findCases(1L)).thenAnswer(inv -> List.of(shortageP1));
        when(cases.findByIdForUpdate(11L)).thenReturn(Optional.of(shortageP1));

        uncoveredP1 = 2;
        when(reconciliation.uncovered(eq(1L), any())).thenAnswer(inv -> Map.of(P1, uncoveredP1, P2, 0));
        when(lines.pendingReturnDecisions(1L)).thenAnswer(inv -> pendingReturns.stream()
                .map(event -> qty(P1, event.getQuantity())).toList());
        when(lines.openReturnQty(1L)).thenReturn(List.of());
        when(events.findPendingReturnDecisionsForUpdate(1L, P1)).thenAnswer(inv -> pendingReturns);

        // Adjustment request 7: STOCK_LOSS of order 1, transit at store 2, P1 x2, DRAFT; 8 belongs to order 2.
        when(adjustments.findById(anyLong())).thenReturn(Optional.empty());
        when(adjustments.findById(7L)).thenReturn(Optional.of(lossRequest(7, 1)));
        when(adjustments.findById(8L)).thenReturn(Optional.of(lossRequest(8, 2)));
        when(orders.transitWriteOff(7L, 2L, P1)).thenReturn(2L);
        when(orders.lossLinked(anyLong())).thenReturn(false);

        when(transferOrders.detail(any(), eq(1L))).thenReturn(detail);
    }

    private static AdjustmentRequest lossRequest(long id, long transferOrderId) {
        return withId(AdjustmentRequest.draft(AdjustmentType.STOCK_LOSS, 2L, new AdjustmentRequest.Source(
                AdjustmentRequest.Source.SourceType.TRANSFER_ORDER, transferOrderId), "Mat hang", 3L, NOW), id);
    }

    /** P1 already has a return decision of 2 with no return opened, so nothing is left uncovered. */
    private void pendingReturnOf2() {
        pendingReturns.add(DiscrepancyResolutionEvent.returnDecision(11L, 2, null, 1L, NOW));
        uncoveredP1 = 0;
    }

    private static TransferDecisionRequest body(long productId, Decision decision, int quantity, Long adjustmentId,
                                                String reason) {
        return new TransferDecisionRequest(productId, decision, quantity, adjustmentId, reason);
    }

    private DiscrepancyResolutionEvent savedEvent() {
        ArgumentCaptor<DiscrepancyResolutionEvent> saved = ArgumentCaptor.forClass(DiscrepancyResolutionEvent.class);
        verify(events).save(saved.capture());
        return saved.getValue();
    }

    private void assertDecided(TransferOrderDetailResponse result) {
        assertThat(result).isSameAs(detail);
        verify(auditLog).save(any());
        verify(notifications).notifyStoreManagers(eq(2L), eq("TRANSFER_DISCREPANCY_DECIDED"), anyString(),
                anyString(), eq(1L));
    }

    private void assertNothingDecided() {
        verify(events, never()).save(any());
        verify(events, never()).delete(any());
        verify(auditLog, never()).save(any());
        verify(notifications, never()).notifyStoreManagers(anyLong(), anyString(), anyString(), anyString(),
                anyLong());
    }

    @Test
    @DisplayName("UTCID01 N: Owner decides late delivery for the whole shortage")
    void utcid01() {
        assertDecided(service.decide(owner, 1, body(P1, Decision.LATE_DELIVERY, 2, null, null)));
        verify(events, never()).save(any());
        verify(notifications, never()).notifyStoreManagers(eq(1L), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("UTCID02 N: central warehouse BM returns the shortage to the source")
    void utcid02() {
        assertDecided(service.decide(centralBm, 1, body(P1, Decision.RETURN_TO_SOURCE, 2, null, "Gui nham")));
        DiscrepancyResolutionEvent saved = savedEvent();
        assertThat(saved.getDiscrepancyCaseId()).isEqualTo(11L);
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getReason()).isEqualTo("Gui nham");
        verify(notifications).notifyStoreManagers(eq(1L), eq("TRANSFER_DISCREPANCY_DECIDED"), anyString(),
                anyString(), eq(1L));
    }

    @Test
    @DisplayName("UTCID03 N: Owner records a loss linked to adjustment request 7")
    void utcid03() {
        assertDecided(service.decide(owner, 1, body(P1, Decision.LOSS, 2, 7L, null)));
        DiscrepancyResolutionEvent saved = savedEvent();
        assertThat(saved.getDiscrepancyCaseId()).isEqualTo(11L);
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getAdjustmentRequestId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("UTCID04 B: late delivery for part of the shortage (1 of 2)")
    void utcid04() {
        assertDecided(service.decide(owner, 1, body(P1, Decision.LATE_DELIVERY, 1, null, null)));
    }

    @Test
    @DisplayName("UTCID05 B: quantity 3 is more than the 2 left -> MSG217")
    void utcid05() {
        assertBusiness(() -> service.decide(owner, 1, body(P1, Decision.LATE_DELIVERY, 3, null, null)),
                HttpStatus.BAD_REQUEST, "MSG217");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID06 B: late delivery releases a pending return decision of 2")
    void utcid06() {
        pendingReturnOf2();
        DiscrepancyResolutionEvent decision = pendingReturns.get(0);
        assertDecided(service.decide(owner, 1, body(P1, Decision.LATE_DELIVERY, 2, null, null)));
        verify(events).delete(decision);
    }

    @Test
    @DisplayName("UTCID07 A: return to source when the pending return already holds all 2 -> MSG217")
    void utcid07() {
        pendingReturnOf2();
        assertBusiness(() -> service.decide(owner, 1, body(P1, Decision.RETURN_TO_SOURCE, 2, null, null)),
                HttpStatus.BAD_REQUEST, "MSG217");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID08 A: BM of store 2 is not a coordinator -> MSG05")
    void utcid08() {
        assertBusiness(() -> service.decide(bmStore2, 1, body(P1, Decision.LATE_DELIVERY, 2, null, null)),
                HttpStatus.FORBIDDEN, "MSG05");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID09 A: transfer order 99999 does not exist -> MSG175")
    void utcid09() {
        assertBusiness(() -> service.decide(owner, 99999, body(P1, Decision.LATE_DELIVERY, 2, null, null)),
                HttpStatus.NOT_FOUND, "MSG175");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID10 A: order is still IN_TRANSIT -> MSG29")
    void utcid10() {
        with(order, "status", TransferOrderStatus.IN_TRANSIT);
        assertBusiness(() -> service.decide(owner, 1, body(P1, Decision.LATE_DELIVERY, 2, null, null)),
                HttpStatus.CONFLICT, "MSG29");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID11 A: product 99999 does not exist -> MSG57")
    void utcid11() {
        assertBusiness(() -> service.decide(owner, 1, body(99999, Decision.LATE_DELIVERY, 2, null, null)),
                HttpStatus.NOT_FOUND, "MSG57");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID12 A: P2 has no open discrepancy case -> MSG216")
    void utcid12() {
        assertBusiness(() -> service.decide(owner, 1, body(P2, Decision.LATE_DELIVERY, 2, null, null)),
                HttpStatus.BAD_REQUEST, "MSG216");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID13 A: loss without an adjustment request -> MSG218")
    void utcid13() {
        assertBusiness(() -> service.decide(owner, 1, body(P1, Decision.LOSS, 2, null, null)),
                HttpStatus.BAD_REQUEST, "MSG218");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID14 A: loss linked to adjustment request 8 of another order -> MSG219")
    void utcid14() {
        assertBusiness(() -> service.decide(owner, 1, body(P1, Decision.LOSS, 2, 8L, null)),
                HttpStatus.BAD_REQUEST, "MSG219");
        assertNothingDecided();
    }
}
