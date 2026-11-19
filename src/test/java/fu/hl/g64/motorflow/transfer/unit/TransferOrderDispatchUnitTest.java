package fu.hl.g64.motorflow.transfer.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferDispatchRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet TransferOrder.dispatch: TransferOrderServiceImpl.dispatch (UC-74), UTCID01-13. */
class TransferOrderDispatchUnitTest extends TransferOrderUnitTestBase {

    private final AuthenticatedUser sourceBm = auth(11, Role.BRANCH_MANAGER, 1L);
    private final AuthenticatedUser destBm = auth(21, Role.BRANCH_MANAGER, 2L);
    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);

    private TransferOrder order;
    private WarehouseTask preparation;
    private final List<TransferOrderLine> orderLines = new ArrayList<>();

    /** Order 1: warehouse 1 -> store 2, AWAITING_DISPATCH, P1 prepared 5, Staff submitted the picking result. */
    @BeforeEach
    void defaultData() {
        order = withId(TransferOrder.create(null, 1L, 2L, 1L, NOW), 1);
        orderLines.add(preparedLine(P1, 5));
        preparation = WarehouseTask.assign(TaskType.DISPATCH_PREPARATION, 1, 1, 31, 11, NOW);
        preparation.submitResult(NOW);
        when(orders.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        when(orders.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(tasks.findOpenForUpdate(TaskType.DISPATCH_PREPARATION, 1L)).thenAnswer(inv -> Optional.of(preparation));
        when(lines.findByTransferOrderIdOrderByProductId(1L)).thenAnswer(inv -> List.copyOf(orderLines));
    }

    private static TransferOrderLine preparedLine(long productId, int prepared) {
        TransferOrderLine line = TransferOrderLine.create(1L, productId, 5, null);
        line.recordPrepared(prepared);
        return line;
    }

    private static TransferDispatchRequest body(long... productQty) {
        List<TransferDispatchRequest.Line> items = new ArrayList<>();
        for (int i = 0; i < productQty.length; i += 2) {
            items.add(new TransferDispatchRequest.Line(productQty[i], (int) productQty[i + 1]));
        }
        return new TransferDispatchRequest(items, "Anh Ba", null, null);
    }

    private void assertNothingPosted() {
        verify(posting, never()).moveAll(any());
        verify(auditLog, never()).save(any());
        assertThat(order.getStatus()).isNotEqualTo(TransferOrderStatus.IN_TRANSIT);
    }

    @SuppressWarnings("unchecked")
    private List<MovePosting> postedMoves() {
        ArgumentCaptor<List<MovePosting>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).moveAll(moves.capture());
        return moves.getValue();
    }

    @Test
    @DisplayName("UTCID01 N: source BM dispatches [{P1, 5}]")
    void utcid01() {
        TransferOrderDetailResponse result = service.dispatch(sourceBm, 1, body(P1, 5));

        assertThat(result.status()).isEqualTo(TransferOrderStatus.IN_TRANSIT);
        assertThat(postedMoves()).singleElement().satisfies(move -> {
            assertThat(move.productId()).isEqualTo(P1);
            assertThat(move.quantity()).isEqualTo(5);
            assertThat(move.fromStoreId()).isEqualTo(1L);
            assertThat(move.fromStatus()).isEqualTo(StockStatus.AVAILABLE);
            assertThat(move.toStoreId()).isEqualTo(2L);
            assertThat(move.toStatus()).isEqualTo(StockStatus.IN_TRANSIT);
        });
        verify(notifications).notifyStoreManagers(eq(2L), eq("TRANSFER_DISPATCHED"), anyString(),
                eq("TRANSFER_ORDER"), eq(1L));
    }

    @Test
    @DisplayName("UTCID02 N: P2 prepared 0, [{P1, 5}, {P2, 0}] moves only P1")
    void utcid02() {
        orderLines.add(preparedLine(P2, 0));

        TransferOrderDetailResponse result = service.dispatch(sourceBm, 1, body(P1, 5, P2, 0));

        assertThat(result.status()).isEqualTo(TransferOrderStatus.IN_TRANSIT);
        assertThat(postedMoves()).singleElement().satisfies(move -> {
            assertThat(move.productId()).isEqualTo(P1);
            assertThat(move.quantity()).isEqualTo(5);
        });
        assertThat(orderLines.get(1).getDispatchedQty()).isZero();
    }

    @Test
    @DisplayName("UTCID03 N: order already dispatched -> returned unchanged")
    void utcid03() {
        order.dispatch(11L, "Anh Ba", null, NOW);

        TransferOrderDetailResponse result = service.dispatch(sourceBm, 1, body(P1, 5));

        assertThat(result.status()).isEqualTo(TransferOrderStatus.IN_TRANSIT);
        verify(posting, never()).moveAll(any());
        verify(auditLog, never()).save(any());
    }

    @Test
    @DisplayName("UTCID04 A: order 99999 -> MSG175")
    void utcid04() {
        assertBusiness(() -> service.dispatch(sourceBm, 99999, body(P1, 5)), HttpStatus.NOT_FOUND, "MSG175");
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID05 A: destination BM -> MSG05")
    void utcid05() {
        assertBusiness(() -> service.dispatch(destBm, 1, body(P1, 5)), HttpStatus.FORBIDDEN, "MSG05");
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID06 A: Owner -> MSG05")
    void utcid06() {
        assertBusiness(() -> service.dispatch(owner, 1, body(P1, 5)), HttpStatus.FORBIDDEN, "MSG05");
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID07 A: picking task still ASSIGNED -> MSG207")
    void utcid07() {
        preparation = WarehouseTask.assign(TaskType.DISPATCH_PREPARATION, 1, 1, 31, 11, NOW);

        assertBusiness(() -> service.dispatch(sourceBm, 1, body(P1, 5)), HttpStatus.CONFLICT, "MSG207");
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID08 B: [{P1, 4}] below the prepared 5 -> MSG208")
    void utcid08() {
        assertBusiness(() -> service.dispatch(sourceBm, 1, body(P1, 4)), HttpStatus.BAD_REQUEST, "MSG208");
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID09 B: [{P1, 6}] above the prepared 5 -> MSG208")
    void utcid09() {
        assertBusiness(() -> service.dispatch(sourceBm, 1, body(P1, 6)), HttpStatus.BAD_REQUEST, "MSG208");
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID10 A: P1 on two lines -> MSG166")
    void utcid10() {
        assertBusiness(() -> service.dispatch(sourceBm, 1, body(P1, 5, P1, 5)), HttpStatus.BAD_REQUEST, "MSG166");
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID11 A: P3 is not on the order -> MSG166")
    void utcid11() {
        assertBusiness(() -> service.dispatch(sourceBm, 1, body(P3, 5)), HttpStatus.BAD_REQUEST, "MSG166");
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID12 A: order has P2 too, body leaves it out -> MSG167")
    void utcid12() {
        orderLines.add(preparedLine(P2, 0));

        assertBusiness(() -> service.dispatch(sourceBm, 1, body(P1, 5)), HttpStatus.BAD_REQUEST, "MSG167");
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID13 B: only P1 prepared 0, [{P1, 0}] -> MSG209")
    void utcid13() {
        orderLines.set(0, preparedLine(P1, 0));

        assertBusiness(() -> service.dispatch(sourceBm, 1, body(P1, 0)), HttpStatus.BAD_REQUEST, "MSG209");
        assertNothingPosted();
    }
}
