package fu.hl.g64.motorflow.transfer.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.CLOCK;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.product;
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

/**
 * Report 5.1, sheet TransferDiscrepancy.decide: TransferDiscrepancyServiceImpl.decide (UC-76, PM 23/11 case 1),
 * UTCID01-14. The receiving store's BM or the Owner chooses late delivery or no replacement for a short part.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferDiscrepancyDecideUnitTest {

    private static final long P1 = 101, P2 = 102;

    @Mock TransferOrderRepository orders;
    @Mock TransferOrderLineRepository lines;
    @Mock TransferReconciliation reconciliation;
    @Mock DiscrepancyCaseRepository cases;
    @Mock DiscrepancyResolutionEventRepository events;
    @Mock ProductRepository products;
    @Mock AuditLogRepository auditLog;
    @Mock NotificationService notifications;
    @Mock TransferOrderService transferOrders;

    private TransferDiscrepancyServiceImpl service;

    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    private final AuthenticatedUser centralBm = auth(2, Role.BRANCH_MANAGER, 1L);
    private final AuthenticatedUser bmStore2 = auth(3, Role.BRANCH_MANAGER, 2L);
    private final AuthenticatedUser bmStore3 = auth(4, Role.BRANCH_MANAGER, 3L);
    private final TransferOrderDetailResponse detail = mock(TransferOrderDetailResponse.class);

    private TransferOrder order;
    private DiscrepancyCase shortageP1;
    private int latePendingP1;

    /** Order 1: warehouse 1 -> store 2, RECONCILING; P1 dispatched 5, received 3, open shortage of 2; P2 received. */
    @BeforeEach
    void defaultData() {
        service = new TransferDiscrepancyServiceImpl(orders, lines, reconciliation, cases, events, products, auditLog,
                notifications, transferOrders, mock(GoodsReceiptNoteLineRepository.class),
                mock(StockBalanceRepository.class), mock(InventoryPostingService.class), CLOCK);

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

        latePendingP1 = 0;
        when(reconciliation.byProduct(eq(1L), any())).thenAnswer(inv -> Map.of(P1,
                new TransferReconciliation.Part(5, 3, 0, 0, 0, 2, 0, 0, 0, 0,
                        new TransferReconciliation.Rules(0, 0, 0, 0, 0, latePendingP1, 0, false))));
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transferOrders.detail(any(), eq(1L))).thenReturn(detail);
    }

    private static TransferDecisionRequest body(long productId, Decision decision, int quantity, String reason) {
        return new TransferDecisionRequest(productId, decision, quantity, null, reason);
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
        verify(notifications).notifyStoreManagers(eq(1L), eq("TRANSFER_DISCREPANCY_DECIDED"), anyString(),
                anyString(), eq(1L));
    }

    private void assertNothingDecided() {
        verify(events, never()).save(any());
        verify(auditLog, never()).save(any());
        verify(notifications, never()).notifyStoreManagers(anyLong(), anyString(), anyString(), anyString(),
                anyLong());
    }

    @Test
    @DisplayName("UTCID01 N: Owner decides late delivery for the whole shortage, pending until a receipt")
    void utcid01() {
        assertDecided(service.decide(owner, 1, body(P1, Decision.LATE_DELIVERY, 2, null)));
        DiscrepancyResolutionEvent saved = savedEvent();
        assertThat(saved.getDiscrepancyCaseId()).isEqualTo(11L);
        assertThat(saved.getResolutionType()).isEqualTo(ResolutionType.LATE_DELIVERY_RECEIVED);
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getConfirmedAt()).isNull();
        verify(notifications, never()).notifyOwners(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("UTCID02 N: the receiving store's BM decides no replacement; the Owner is told")
    void utcid02() {
        assertDecided(service.decide(bmStore2, 1, body(P1, Decision.NO_REPLACEMENT, 2, "  Khong can nua ")));
        DiscrepancyResolutionEvent saved = savedEvent();
        assertThat(saved.getResolutionType()).isEqualTo(ResolutionType.NO_REPLACEMENT);
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getReason()).isEqualTo("Khong can nua");
        assertThat(saved.getConfirmedAt()).isNull();
        verify(notifications).notifyOwners(eq("TRANSFER_DISCREPANCY_DECIDED"), anyString(), anyString(), eq(1L));
    }

    @Test
    @DisplayName("UTCID03 B: late delivery for part of the shortage (1 of 2)")
    void utcid03() {
        assertDecided(service.decide(owner, 1, body(P1, Decision.LATE_DELIVERY, 1, null)));
        assertThat(savedEvent().getQuantity()).isEqualTo(1);
    }

    @Test
    @DisplayName("UTCID04 B: quantity 3 is more than the 2 awaiting a decision -> MSG217")
    void utcid04() {
        assertBusiness(() -> service.decide(owner, 1, body(P1, Decision.LATE_DELIVERY, 3, null)),
                HttpStatus.BAD_REQUEST, "MSG217");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID05 B: the 2 short are already to come later -> no replacement of 1 is MSG217")
    void utcid05() {
        latePendingP1 = 2;
        assertBusiness(() -> service.decide(bmStore2, 1, body(P1, Decision.NO_REPLACEMENT, 1, null)),
                HttpStatus.BAD_REQUEST, "MSG217");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID06 A: return to the source is no longer offered -> 400 MSG345")
    void utcid06() {
        assertBusiness(() -> service.decide(owner, 1, body(P1, Decision.RETURN_TO_SOURCE, 2, null)),
                HttpStatus.BAD_REQUEST, "MSG345");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID07 A: a loss is no longer decided, closing writes it off -> 400 MSG345")
    void utcid07() {
        assertBusiness(() -> service.decide(owner, 1, body(P1, Decision.LOSS, 2, null)),
                HttpStatus.BAD_REQUEST, "MSG345");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID08 A: the central warehouse BM is the sender, not the receiver -> MSG05")
    void utcid08() {
        assertBusiness(() -> service.decide(centralBm, 1, body(P1, Decision.LATE_DELIVERY, 2, null)),
                HttpStatus.FORBIDDEN, "MSG05");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID09 A: transfer order 99999 does not exist -> MSG175")
    void utcid09() {
        assertBusiness(() -> service.decide(owner, 99999, body(P1, Decision.LATE_DELIVERY, 2, null)),
                HttpStatus.NOT_FOUND, "MSG175");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID10 A: order is still IN_TRANSIT -> MSG29")
    void utcid10() {
        with(order, "status", TransferOrderStatus.IN_TRANSIT);
        assertBusiness(() -> service.decide(owner, 1, body(P1, Decision.LATE_DELIVERY, 2, null)),
                HttpStatus.CONFLICT, "MSG29");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID11 A: product 99999 does not exist -> MSG57")
    void utcid11() {
        assertBusiness(() -> service.decide(owner, 1, body(99999, Decision.LATE_DELIVERY, 2, null)),
                HttpStatus.NOT_FOUND, "MSG57");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID12 A: P2 has no open discrepancy case -> MSG216")
    void utcid12() {
        assertBusiness(() -> service.decide(owner, 1, body(P2, Decision.LATE_DELIVERY, 2, null)),
                HttpStatus.BAD_REQUEST, "MSG216");
        assertNothingDecided();
    }

    @Test
    @DisplayName("UTCID13 B: no replacement for 1 after 1 is to come later")
    void utcid13() {
        latePendingP1 = 1;
        assertDecided(service.decide(owner, 1, body(P1, Decision.NO_REPLACEMENT, 1, null)));
        assertThat(savedEvent().getResolutionType()).isEqualTo(ResolutionType.NO_REPLACEMENT);
    }

    @Test
    @DisplayName("UTCID14 A: the BM of another store (3) -> MSG05")
    void utcid14() {
        assertBusiness(() -> service.decide(bmStore3, 1, body(P1, Decision.NO_REPLACEMENT, 2, null)),
                HttpStatus.FORBIDDEN, "MSG05");
        assertNothingDecided();
    }
}
