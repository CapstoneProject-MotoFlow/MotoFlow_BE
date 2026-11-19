package fu.hl.g64.motorflow.transfer.unit;

import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.ReplenishmentDetailResponse;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequestLine;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import fu.hl.g64.motorflow.transfer.helper.RequestedParts;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestLineRepository;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.service.impl.ReplenishmentRequestServiceImpl;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.CLOCK;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.account;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.product;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.store;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet ReplenishmentRequest.create: ReplenishmentRequestServiceImpl.create (UC-68), UTCID01-11. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplenishmentRequestCreateUnitTest {

    private static final long P1 = 101, P2 = 102, P9 = 109, MISSING = 99999;
    private static final long REQUEST_ID = 500, CENTRAL_BM = 11;

    @Mock ReplenishmentRequestRepository requests;
    @Mock ReplenishmentRequestLineRepository lines;
    @Mock TransferOrderRepository transferOrders;
    @Mock TransferOrderLineRepository transferLines;
    @Mock ProductRepository products;
    @Mock StoreRepository stores;
    @Mock UserAccountRepository users;
    @Mock NotificationService notifications;
    @Mock TransferReconciliation reconciliation;
    @Mock AuditLogRepository auditLog;

    private ReplenishmentRequestServiceImpl service;
    private final List<ReplenishmentRequestLine> savedLines = new ArrayList<>();

    private final AuthenticatedUser bmStore2 = auth(21, Role.BRANCH_MANAGER, 2L);
    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    private final AuthenticatedUser bmStore5 = auth(25, Role.BRANCH_MANAGER, 5L);

    @BeforeEach
    void defaultData() {
        service = new ReplenishmentRequestServiceImpl(requests, lines, transferOrders, transferLines, products, stores,
                new StoreScope(stores), users, notifications, new RequestedParts(products), reconciliation, auditLog,
                CLOCK);
        // Store 1 is the central warehouse; 2, 3 ACTIVE; 4 INACTIVE; 5 PLANNED.
        Map<Long, Store> all = Map.of(1L, store(1, StoreStatus.ACTIVE, true), 2L, store(2, StoreStatus.ACTIVE, false),
                3L, store(3, StoreStatus.ACTIVE, false), 4L, store(4, StoreStatus.INACTIVE, false),
                5L, store(5, StoreStatus.PLANNED, false));
        when(stores.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(all.get(inv.<Long>getArgument(0))));
        when(stores.findByIsCentralTrue()).thenReturn(Optional.of(all.get(1L)));
        // P1, P2 ACTIVE; P9 DISCONTINUED.
        Map<Long, Product> parts = Map.of(P1, product(P1, "P1", ProductStatus.ACTIVE),
                P2, product(P2, "P2", ProductStatus.ACTIVE), P9, product(P9, "P9", ProductStatus.DISCONTINUED));
        when(products.findAllById(any())).thenAnswer(inv -> {
            List<Product> found = new ArrayList<>();
            for (Long id : inv.<Iterable<Long>>getArgument(0)) {
                if (parts.containsKey(id) && !found.contains(parts.get(id))) {
                    found.add(parts.get(id));
                }
            }
            return found;
        });
        when(requests.save(any())).thenAnswer(inv -> withId(inv.<ReplenishmentRequest>getArgument(0), REQUEST_ID));
        when(lines.saveAll(any())).thenAnswer(inv -> {
            savedLines.addAll(inv.<Collection<ReplenishmentRequestLine>>getArgument(0));
            return List.copyOf(savedLines);
        });
        when(lines.findByReplenishmentRequestIdOrderById(REQUEST_ID)).thenAnswer(inv -> List.copyOf(savedLines));
        when(users.findById(anyLong())).thenAnswer(inv -> Optional.of(account(inv.getArgument(0), Role.OWNER, null)));
        when(users.findByRoleAndStatus(Role.OWNER, AccountStatus.ACTIVE)).thenReturn(List.of(account(1, Role.OWNER, null)));
        when(users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, 1L, AccountStatus.ACTIVE))
                .thenReturn(List.of(account(CENTRAL_BM, Role.BRANCH_MANAGER, 1L)));
        when(transferLines.plannedByProduct(anyLong())).thenReturn(List.of());
        when(reconciliation.fulfilled(anyLong())).thenReturn(Map.of());
        when(transferOrders.findLinkedOrders(anyLong())).thenReturn(List.of());
        when(requests.findPurchaseOrders(anyLong())).thenReturn(List.of());
    }

    private static ReplenishmentCreateRequest body(Long storeId, String note, long... productQty) {
        List<ReplenishmentCreateRequest.Line> items = new ArrayList<>();
        for (int i = 0; i < productQty.length; i += 2) {
            items.add(new ReplenishmentCreateRequest.Line(productQty[i], (int) productQty[i + 1]));
        }
        return new ReplenishmentCreateRequest(storeId, note, items);
    }

    private void assertNothingSaved() {
        verify(requests, never()).save(any());
        verify(lines, never()).saveAll(any());
    }

    @Test
    @DisplayName("UTCID01 N: BM of store 2, note with spaces, [{P1, 5}, {P2, 3}]")
    void utcid01() {
        ReplenishmentDetailResponse result = service.create(bmStore2, body(null, "  Het ma phanh  ", P1, 5, P2, 3));

        assertThat(result.requestingStoreId()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(ReplenishmentRequestStatus.AWAITING_COORDINATION);
        assertThat(result.note()).isEqualTo("Het ma phanh");
        assertThat(result.lines()).extracting(ReplenishmentDetailResponse.Line::sku, ReplenishmentDetailResponse.Line::requestedQty)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("P1", 5), org.assertj.core.groups.Tuple.tuple("P2", 3));
        verify(notifications).notify(eq(CENTRAL_BM), eq("REPLENISHMENT_REQUESTED"), anyString(),
                eq("REPLENISHMENT_REQUEST"), eq(REQUEST_ID));
    }

    @Test
    @DisplayName("UTCID02 B: BM of store 2, note null, one line [{P1, 1}]")
    void utcid02() {
        ReplenishmentDetailResponse result = service.create(bmStore2, body(null, null, P1, 1));

        assertThat(result.requestingStoreId()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(ReplenishmentRequestStatus.AWAITING_COORDINATION);
        assertThat(result.note()).isNull();
        assertThat(result.lines()).hasSize(1);
    }

    @Test
    @DisplayName("UTCID03 N: Owner requests for store 2")
    void utcid03() {
        ReplenishmentDetailResponse result = service.create(owner, body(2L, "  Het ma phanh  ", P1, 5, P2, 3));

        assertThat(result.requestingStoreId()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(ReplenishmentRequestStatus.AWAITING_COORDINATION);
        assertThat(result.note()).isEqualTo("Het ma phanh");
        assertThat(result.lines()).hasSize(2);
        verify(notifications).notify(eq(CENTRAL_BM), eq("REPLENISHMENT_REQUESTED"), anyString(),
                eq("REPLENISHMENT_REQUEST"), eq(REQUEST_ID));
        verify(notifications, never()).notify(eq(1L), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("UTCID04 N: Owner requests for the PLANNED store 5")
    void utcid04() {
        ReplenishmentDetailResponse result = service.create(owner, body(5L, "  Het ma phanh  ", P1, 5, P2, 3));

        assertThat(result.requestingStoreId()).isEqualTo(5);
        assertThat(result.status()).isEqualTo(ReplenishmentRequestStatus.AWAITING_COORDINATION);
    }

    @Test
    @DisplayName("UTCID05 A: Owner without storeId -> MSG02")
    void utcid05() {
        assertBusiness(() -> service.create(owner, body(null, "  Het ma phanh  ", P1, 5, P2, 3)),
                HttpStatus.BAD_REQUEST, "MSG02");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID06 A: BM of store 2 asks for store 3 -> MSG05")
    void utcid06() {
        assertBusiness(() -> service.create(bmStore2, body(3L, "  Het ma phanh  ", P1, 5, P2, 3)),
                HttpStatus.FORBIDDEN, "MSG05");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID07 A: Owner asks for the INACTIVE store 4 -> MSG46")
    void utcid07() {
        assertBusiness(() -> service.create(owner, body(4L, "  Het ma phanh  ", P1, 5, P2, 3)),
                HttpStatus.BAD_REQUEST, "MSG46");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID08 A: BM of the PLANNED store 5 -> MSG46")
    void utcid08() {
        assertBusiness(() -> service.create(bmStore5, body(null, "  Het ma phanh  ", P1, 5, P2, 3)),
                HttpStatus.BAD_REQUEST, "MSG46");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID09 A: P1 on two lines -> MSG93")
    void utcid09() {
        assertBusiness(() -> service.create(bmStore2, body(null, "  Het ma phanh  ", P1, 5, P1, 2)),
                HttpStatus.BAD_REQUEST, "MSG93");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID10 A: part 99999 does not exist -> MSG57")
    void utcid10() {
        assertBusiness(() -> service.create(bmStore2, body(null, "  Het ma phanh  ", MISSING, 1)),
                HttpStatus.NOT_FOUND, "MSG57");
        assertNothingSaved();
    }

    @Test
    @DisplayName("UTCID11 A: P9 is DISCONTINUED -> MSG94")
    void utcid11() {
        assertBusiness(() -> service.create(bmStore2, body(null, "  Het ma phanh  ", P9, 1)),
                HttpStatus.BAD_REQUEST, "MSG94");
        assertNothingSaved();
    }
}
