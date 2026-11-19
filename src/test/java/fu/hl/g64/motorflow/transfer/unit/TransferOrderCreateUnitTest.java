package fu.hl.g64.motorflow.transfer.unit;

import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.inventory.dto.StockAvailabilityResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequestLine;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.account;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet TransferOrder.create: TransferOrderServiceImpl.create (UC-71), UTCID01-19. */
class TransferOrderCreateUnitTest extends TransferOrderUnitTestBase {

    private static final long ORDER_ID = 900, SOURCE_BM = 11;

    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    private final AuthenticatedUser centralBm = auth(SOURCE_BM, Role.BRANCH_MANAGER, 1L);
    private final AuthenticatedUser bmStore2 = auth(21, Role.BRANCH_MANAGER, 2L);

    private final List<TransferOrderLine> savedLines = new ArrayList<>();
    private ReplenishmentRequest request3;

    @BeforeEach
    void defaultData() {
        // Warehouse 1 (central): P1 sellable 10, P2 sellable 0.
        when(inventory.availability(any(), any())).thenReturn(List.of(
                new StockAvailabilityResponse(P1, "P1", 1, "Store 1", true, 10),
                new StockAvailabilityResponse(P2, "P2", 1, "Store 1", true, 0)));
        // Replenishment request 3 of store 2 awaiting coordination, P1 x6; request 8 CLOSED.
        request3 = withId(ReplenishmentRequest.create(2L, null, 21L, NOW), 3);
        ReplenishmentRequest request8 = with(withId(ReplenishmentRequest.create(2L, null, 21L, NOW), 8),
                "status", ReplenishmentRequestStatus.CLOSED);
        when(requests.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        when(requests.findByIdForUpdate(3L)).thenReturn(Optional.of(request3));
        when(requests.findByIdForUpdate(8L)).thenReturn(Optional.of(request8));
        when(requestLines.findByReplenishmentRequestIdOrderById(3L))
                .thenReturn(List.of(ReplenishmentRequestLine.create(3L, P1, 6)));
        when(lines.plannedByProduct(anyLong())).thenReturn(List.of());
        // Discrepancy case 11 open: order 7 to store 2 is short P1 x2.
        DiscrepancyCase case11 = withId(DiscrepancyCase.transfer(7, P1, 2, NOW), 11);
        when(cases.findAllById(any())).thenReturn(List.of(case11));
        when(orders.findAllById(any())).thenReturn(List.of(withId(TransferOrder.create(null, 1L, 2L, 1L, NOW), 7)));

        when(orders.save(any())).thenAnswer(inv -> withId(inv.<TransferOrder>getArgument(0), ORDER_ID));
        when(lines.saveAll(any())).thenAnswer(inv -> {
            savedLines.addAll(inv.<Collection<TransferOrderLine>>getArgument(0));
            return List.copyOf(savedLines);
        });
        when(lines.findByTransferOrderIdOrderByProductId(ORDER_ID)).thenAnswer(inv -> List.copyOf(savedLines));
        when(users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, 1L, AccountStatus.ACTIVE))
                .thenReturn(List.of(account(SOURCE_BM, Role.BRANCH_MANAGER, 1L)));
    }

    private static TransferOrderCreateRequest body(long source, long dest, Long requestId, long productId, int qty,
                                                   Long makeupForCaseId) {
        return new TransferOrderCreateRequest(requestId, source, dest,
                List.of(new TransferOrderCreateRequest.Line(productId, qty, makeupForCaseId)));
    }

    private static TransferOrderCreateRequest body(long source, long dest, Long requestId, long productId, int qty) {
        return body(source, dest, requestId, productId, qty, null);
    }

    private void assertCreated(TransferOrderDetailResponse result, long dest) {
        assertThat(result.status()).isEqualTo(TransferOrderStatus.AWAITING_DISPATCH);
        assertThat(result.sourceStoreId()).isEqualTo(1);
        assertThat(result.destStoreId()).isEqualTo(dest);
        verify(auditLog).save(argThat(log -> "TRANSFER_ORDER_CREATED".equals(log.getAction())));
    }

    private void assertNothingSaved() {
        verify(orders, never()).save(any());
        verify(lines, never()).saveAll(any());
    }

    @Test
    @DisplayName("UTCID01 N: Owner, 1 -> 2, [{P1, 5}]")
    void utcid01() {
        assertCreated(service.create(owner, body(1, 2, null, P1, 5)), 2);
        verify(notifications).notify(eq(SOURCE_BM), eq("TRANSFER_CREATED"), anyString(), eq("TRANSFER_ORDER"),
                eq(ORDER_ID));
    }

    @Test
    @DisplayName("UTCID02 N: central BM, 1 -> 2, [{P1, 5}]")
    void utcid02() {
        assertCreated(service.create(centralBm, body(1, 2, null, P1, 5)), 2);
        verify(notifications, never()).notify(eq(SOURCE_BM), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("UTCID03 B: [{P1, 10}] = all that is sellable")
    void utcid03() {
        assertCreated(service.create(owner, body(1, 2, null, P1, 10)), 2);
    }

    @Test
    @DisplayName("UTCID04 B: [{P1, 11}] -> MSG201")
    void utcid04() {
        assertBusiness(() -> service.create(owner, body(1, 2, null, P1, 11)), HttpStatus.BAD_REQUEST, "MSG201");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID05 A: [{P2, 1}] with P2 sellable 0 -> MSG201")
    void utcid05() {
        assertBusiness(() -> service.create(owner, body(1, 2, null, P2, 1)), HttpStatus.BAD_REQUEST, "MSG201");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID06 A: BM of store 2 -> MSG05")
    void utcid06() {
        assertBusiness(() -> service.create(bmStore2, body(1, 2, null, P1, 5)), HttpStatus.FORBIDDEN, "MSG05");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID07 A: source 2 -> destination 2 -> MSG200")
    void utcid07() {
        assertBusiness(() -> service.create(owner, body(2, 2, null, P1, 5)), HttpStatus.BAD_REQUEST, "MSG200");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID08 A: destination 4 INACTIVE -> MSG46")
    void utcid08() {
        assertBusiness(() -> service.create(owner, body(1, 4, null, P1, 5)), HttpStatus.BAD_REQUEST, "MSG46");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID09 N: Owner sends to the PLANNED store 5")
    void utcid09() {
        assertCreated(service.create(owner, body(1, 5, null, P1, 5)), 5);
    }

    @Test
    @DisplayName("UTCID10 A: central BM sends to the PLANNED store 5 -> MSG46")
    void utcid10() {
        assertBusiness(() -> service.create(centralBm, body(1, 5, null, P1, 5)), HttpStatus.BAD_REQUEST, "MSG46");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID11 N: for request 3, [{P1, 5}] -> request IN_PROGRESS")
    void utcid11() {
        TransferOrderDetailResponse result = service.create(owner, body(1, 2, 3L, P1, 5));

        assertCreated(result, 2);
        assertThat(result.replenishmentRequestId()).isEqualTo(3L);
        assertThat(request3.getStatus()).isEqualTo(ReplenishmentRequestStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("UTCID12 B: for request 3, [{P1, 6}] = all that remains")
    void utcid12() {
        assertCreated(service.create(owner, body(1, 2, 3L, P1, 6)), 2);
        assertThat(request3.getStatus()).isEqualTo(ReplenishmentRequestStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("UTCID13 B: for request 3, [{P1, 7}] -> MSG202")
    void utcid13() {
        assertBusiness(() -> service.create(owner, body(1, 2, 3L, P1, 7)), HttpStatus.BAD_REQUEST, "MSG202");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID14 A: request 3 of store 2 sent to store 3 -> MSG203")
    void utcid14() {
        assertBusiness(() -> service.create(owner, body(1, 3, 3L, P1, 5)), HttpStatus.BAD_REQUEST, "MSG203");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID15 A: request 3 has no P2 -> MSG204")
    void utcid15() {
        assertBusiness(() -> service.create(owner, body(1, 2, 3L, P2, 1)), HttpStatus.BAD_REQUEST, "MSG204");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID16 A: request 8 is CLOSED -> MSG29")
    void utcid16() {
        assertBusiness(() -> service.create(owner, body(1, 2, 8L, P1, 5)), HttpStatus.CONFLICT, "MSG29");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID17 A: request 99999 does not exist -> MSG95")
    void utcid17() {
        assertBusiness(() -> service.create(owner, body(1, 2, 99999L, P1, 5)), HttpStatus.NOT_FOUND, "MSG95");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID18 B: make-up line P1 x2 for case 11 (short 2)")
    void utcid18() {
        TransferOrderDetailResponse result = service.create(owner, body(1, 2, null, P1, 2, 11L));

        assertCreated(result, 2);
        assertThat(result.lines()).singleElement().satisfies(line -> assertThat(line.makeupForCaseId()).isEqualTo(11L));
    }

    @Test
    @DisplayName("UTCID19 A: make-up line P1 x3 for case 11 (short 2) -> MSG206")
    void utcid19() {
        assertBusiness(() -> service.create(owner, body(1, 2, null, P1, 3, 11L)), HttpStatus.BAD_REQUEST, "MSG206");
        assertNothingSaved();
    }
}
