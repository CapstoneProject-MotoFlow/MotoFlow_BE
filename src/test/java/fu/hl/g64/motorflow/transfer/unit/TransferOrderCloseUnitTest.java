package fu.hl.g64.motorflow.transfer.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequestLine;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet TransferOrder.close: TransferOrderServiceImpl.close (UC-77), UTCID01-13. */
class TransferOrderCloseUnitTest extends TransferOrderUnitTestBase {

    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    private final AuthenticatedUser centralBm = auth(11, Role.BRANCH_MANAGER, 1L);
    private final AuthenticatedUser bmStore2 = auth(21, Role.BRANCH_MANAGER, 2L);

    private TransferOrder order;
    private TransferOrderLine lineP1;
    private int sourceSellable;

    /** Order 1: warehouse 1 -> store 2, RECONCILING; P1 dispatched 5, received 5. */
    @BeforeEach
    void defaultData() {
        order = reconciling(null);
        lineP1 = TransferOrderLine.create(1L, P1, 5, null);
        lineP1.recordPrepared(5);
        lineP1.recordDispatched(5);
        figures.put(P1, new TransferReconciliation.Part(5, 5, 0, 0, 0, 0));
        sourceSellable = 10;
        when(orders.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        when(orders.findByIdForUpdate(1L)).thenAnswer(inv -> Optional.of(order));
        when(lines.findByTransferOrderIdOrderByProductId(1L)).thenAnswer(inv -> List.of(lineP1));
        when(lines.openReceiptQty(1L)).thenReturn(List.of());
        when(guide.parts(eq(1L), any(), any())).thenAnswer(inv -> Map.of(P1,
                new PickingGuide.Part(sourceSellable, 0, null, null, List.of(), List.of(), List.of())));
    }

    private static TransferOrder reconciling(Long requestId) {
        TransferOrder order = withId(TransferOrder.create(requestId, 1L, 2L, 1L, NOW), 1);
        order.dispatch(11L, null, null, NOW);
        return with(order, "status", TransferOrderStatus.RECONCILING);
    }

    /** P1 received {@code received} of 5 and the source confirmed {@code confirmed} of the excess. */
    private void excess(int received, int confirmed, int sellable) {
        figures.put(P1, new TransferReconciliation.Part(5, received, received - 5, 0, 0, 0));
        lineP1.confirmExtraDispatch(confirmed);
        sourceSellable = sellable;
    }

    @SuppressWarnings("unchecked")
    private List<MovePosting> postedMoves() {
        ArgumentCaptor<List<MovePosting>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).moveAll(moves.capture());
        return moves.getValue();
    }

    private void assertClosed(TransferOrderDetailResponse result) {
        assertThat(result.status()).isEqualTo(TransferOrderStatus.CLOSED);
        verify(warehouseTasks).cancelForDocument(TaskType.TRANSFER_RECEIVING, 1L);
        verify(notifications).notifyStoreManagers(eq(1L), eq("TRANSFER_CLOSED"), anyString(), anyString(), eq(1L));
        verify(notifications).notifyStoreManagers(eq(2L), eq("TRANSFER_CLOSED"), anyString(), anyString(), eq(1L));
    }

    private void assertNotClosed() {
        assertThat(order.getStatus()).isNotEqualTo(TransferOrderStatus.CLOSED);
        verify(posting, never()).moveAll(any());
        verify(auditLog, never()).save(any());
    }

    private void assertExtraDispatch(int quantity) {
        assertThat(postedMoves()).singleElement().satisfies(move -> {
            assertThat(move.productId()).isEqualTo(P1);
            assertThat(move.quantity()).isEqualTo(quantity);
            assertThat(move.movementType()).isEqualTo(MovementType.TRANSFER_EXTRA_DISPATCH);
            assertThat(move.fromStoreId()).isEqualTo(1L);
            assertThat(move.fromStatus()).isEqualTo(StockStatus.AVAILABLE);
            assertThat(move.toStoreId()).isEqualTo(2L);
        });
    }

    @Test
    @DisplayName("UTCID01 N: Owner closes an order received in full")
    void utcid01() {
        assertClosed(service.close(owner, 1));
        assertThat(postedMoves()).isEmpty();
    }

    @Test
    @DisplayName("UTCID02 N: central BM closes it")
    void utcid02() {
        assertClosed(service.close(centralBm, 1));
        assertThat(postedMoves()).isEmpty();
    }

    @Test
    @DisplayName("UTCID03 N: excess 1 confirmed, source has 3 -> extra dispatch P1 x1")
    void utcid03() {
        excess(6, 1, 3);

        assertClosed(service.close(owner, 1));
        assertExtraDispatch(1);
    }

    @Test
    @DisplayName("UTCID04 B: excess 2 confirmed, source has exactly 2 -> extra dispatch P1 x2")
    void utcid04() {
        excess(7, 2, 2);

        assertClosed(service.close(owner, 1));
        assertExtraDispatch(2);
    }

    @Test
    @DisplayName("UTCID05 B: excess 2 confirmed, source has 1 -> MSG223")
    void utcid05() {
        excess(7, 2, 1);

        assertBusiness(() -> service.close(owner, 1), HttpStatus.CONFLICT, "MSG223");
        assertNotClosed();
    }

    @Test
    @DisplayName("UTCID06 N: order for request 3, nothing left to send -> request CLOSED")
    void utcid06() {
        order = reconciling(3L);
        ReplenishmentRequest request3 = withId(ReplenishmentRequest.create(2L, null, 21L, NOW), 3);
        request3.startCoordination();
        when(requests.findByIdForUpdate(3L)).thenReturn(Optional.of(request3));
        TransferOrderRepository.LinkedOrder linked = mock(TransferOrderRepository.LinkedOrder.class);
        when(linked.getTransferOrder()).thenAnswer(inv -> order);
        when(orders.findLinkedOrders(3L)).thenReturn(List.of(linked));
        when(reconciliation.fulfilled(3L)).thenReturn(Map.of(P1, 5L));
        when(requestLines.findByReplenishmentRequestIdOrderById(3L))
                .thenReturn(List.of(ReplenishmentRequestLine.create(3L, P1, 5)));

        assertClosed(service.close(owner, 1));
        assertThat(request3.getStatus()).isEqualTo(ReplenishmentRequestStatus.CLOSED);
    }

    @Test
    @DisplayName("UTCID07 N: order already CLOSED -> returned unchanged")
    void utcid07() {
        order.close(1L, NOW);

        TransferOrderDetailResponse result = service.close(owner, 1);

        assertThat(result.status()).isEqualTo(TransferOrderStatus.CLOSED);
        verify(posting, never()).moveAll(any());
        verify(auditLog, never()).save(any());
    }

    @Test
    @DisplayName("UTCID08 A: order still IN_TRANSIT -> MSG29")
    void utcid08() {
        with(order, "status", TransferOrderStatus.IN_TRANSIT);

        assertBusiness(() -> service.close(owner, 1), HttpStatus.CONFLICT, "MSG29");
        assertNotClosed();
    }

    @Test
    @DisplayName("UTCID09 A: P1 received 3 of 5, 2 without a result -> MSG221")
    void utcid09() {
        figures.put(P1, new TransferReconciliation.Part(5, 3, 0, 0, 0, 2));

        assertBusiness(() -> service.close(owner, 1), HttpStatus.CONFLICT, "MSG221");
        assertNotClosed();
    }

    @Test
    @DisplayName("UTCID10 A: excess 1 not confirmed by the source -> MSG222")
    void utcid10() {
        excess(6, 0, 10);

        assertBusiness(() -> service.close(owner, 1), HttpStatus.CONFLICT, "MSG222");
        assertNotClosed();
    }

    @Test
    @DisplayName("UTCID11 A: an open receipt still expects P1 x2 -> MSG225")
    void utcid11() {
        when(lines.openReceiptQty(1L)).thenReturn(List.of(qty(P1, 2)));

        assertBusiness(() -> service.close(owner, 1), HttpStatus.CONFLICT, "MSG225");
        assertNotClosed();
    }

    @Test
    @DisplayName("UTCID12 A: BM of store 2 -> MSG05")
    void utcid12() {
        assertBusiness(() -> service.close(bmStore2, 1), HttpStatus.FORBIDDEN, "MSG05");
        assertNotClosed();
    }

    @Test
    @DisplayName("UTCID13 A: order 99999 -> MSG175")
    void utcid13() {
        assertBusiness(() -> service.close(owner, 99999), HttpStatus.NOT_FOUND, "MSG175");
        assertNotClosed();
    }
}
