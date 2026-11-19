package fu.hl.g64.motorflow.stockcount.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequest;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequestProduct;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestCreateRequest;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.List;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet StockCountRequest.create: StockCountRequestServiceImpl.create (UC-109), UTCID01-14. */
class StockCountRequestCreateUnitTest extends StockCountUnitTestBase {

    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    private final AuthenticatedUser bmStore2 = auth(21, Role.BRANCH_MANAGER, 2L);
    private final CountRequestResponse response = mock(CountRequestResponse.class);

    /** 18/11/2026, period 11/2026 OPEN; store 2 ACTIVE with area "Ke A"; store 4 INACTIVE; category 3 ACTIVE, 9 not. */
    @BeforeEach
    void defaultData() {
        when(requests.save(any())).thenAnswer(inv -> withId(inv.<StockCountRequest>getArgument(0), 1));
        when(mapper.toRequest(any())).thenReturn(response);
    }

    private static CountRequestCreateRequest body(long storeId, ScopeType scope, String note, Long categoryId,
                                                  List<Long> productIds) {
        return new CountRequestCreateRequest(storeId, scope, note, "Kiem cuoi quy", productIds, categoryId);
    }

    private StockCountRequest assertRequested(CountRequestResponse result) {
        assertThat(result).isSameAs(response);
        ArgumentCaptor<StockCountRequest> saved = ArgumentCaptor.forClass(StockCountRequest.class);
        verify(requests).save(saved.capture());
        StockCountRequest request = saved.getValue();
        assertThat(request.getStatus()).isEqualTo(StockCountRequestStatus.PENDING);
        assertThat(request.getStoreId()).isEqualTo(2L);
        assertThat(request.getRequestedBy()).isEqualTo(1L);
        verify(notifications).notifyStoreManagers(eq(2L), eq("STOCK_COUNT_REQUESTED"), anyString(), anyString(),
                eq(1L));
        return request;
    }

    private void assertNotRequested() {
        verify(requests, never()).save(any());
        verify(requestProducts, never()).save(any());
        verify(notifications, never()).notifyStoreManagers(anyLong(), anyString(), anyString(), anyString(),
                anyLong());
    }

    @Test
    @DisplayName("UTCID01 N: Owner requests a whole-warehouse count of store 2")
    void utcid01() {
        StockCountRequest request = assertRequested(requestService.create(owner,
                body(2, ScopeType.WHOLE, null, null, null)));
        assertThat(request.getScopeType()).isEqualTo(ScopeType.WHOLE);
        verify(requestProducts, never()).save(any());
    }

    @Test
    @DisplayName("UTCID02 N: Owner requests a count of products [P1, P2]")
    void utcid02() {
        StockCountRequest request = assertRequested(requestService.create(owner,
                body(2, ScopeType.PRODUCTS, null, null, List.of(P1, P2))));
        assertThat(request.getScopeType()).isEqualTo(ScopeType.PRODUCTS);
        verify(requestProducts, times(2)).save(any(StockCountRequestProduct.class));
    }

    @Test
    @DisplayName("UTCID03 N: Owner requests a count of area \"Ke A\"")
    void utcid03() {
        StockCountRequest request = assertRequested(requestService.create(owner,
                body(2, ScopeType.AREA, "Ke A", null, null)));
        assertThat(request.getScopeNote()).isEqualTo("Ke A");
    }

    @Test
    @DisplayName("UTCID04 N: Owner requests a count of category 3")
    void utcid04() {
        StockCountRequest request = assertRequested(requestService.create(owner,
                body(2, ScopeType.CATEGORY, null, 3L, null)));
        assertThat(request.getPartCategoryId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("UTCID05 A: a Branch Manager cannot request a count -> MSG05")
    void utcid05() {
        assertBusiness(() -> requestService.create(bmStore2, body(2, ScopeType.WHOLE, null, null, null)),
                HttpStatus.FORBIDDEN, "MSG05");
        assertNotRequested();
    }

    @Test
    @DisplayName("UTCID06 A: store 4 is INACTIVE -> MSG242")
    void utcid06() {
        assertBusiness(() -> requestService.create(owner, body(4, ScopeType.WHOLE, null, null, null)),
                HttpStatus.BAD_REQUEST, "MSG242");
        assertNotRequested();
    }

    @Test
    @DisplayName("UTCID07 A: store 99999 does not exist -> MSG242")
    void utcid07() {
        assertBusiness(() -> requestService.create(owner, body(99999, ScopeType.WHOLE, null, null, null)),
                HttpStatus.BAD_REQUEST, "MSG242");
        assertNotRequested();
    }

    @Test
    @DisplayName("UTCID08 A: period 11/2026 is CLOSED -> MSG232")
    void utcid08() {
        periodStatus.put(11L, InventoryPeriodStatus.CLOSED);
        assertBusiness(() -> requestService.create(owner, body(2, ScopeType.WHOLE, null, null, null)),
                HttpStatus.CONFLICT, "MSG232");
        assertNotRequested();
    }

    @Test
    @DisplayName("UTCID09 A: area \"Ke Z\" is not in store 2 -> MSG02")
    void utcid09() {
        assertBusiness(() -> requestService.create(owner, body(2, ScopeType.AREA, "Ke Z", null, null)),
                HttpStatus.BAD_REQUEST, "MSG02");
        assertNotRequested();
    }

    @Test
    @DisplayName("UTCID10 A: WHOLE scope with a product list -> MSG02")
    void utcid10() {
        assertBusiness(() -> requestService.create(owner, body(2, ScopeType.WHOLE, null, null, List.of(P1, P2))),
                HttpStatus.BAD_REQUEST, "MSG02");
        assertNotRequested();
    }

    @Test
    @DisplayName("UTCID11 A: product list repeats P1 -> MSG02")
    void utcid11() {
        assertBusiness(() -> requestService.create(owner, body(2, ScopeType.PRODUCTS, null, null, List.of(P1, P1))),
                HttpStatus.BAD_REQUEST, "MSG02");
        assertNotRequested();
    }

    @Test
    @DisplayName("UTCID12 B: PRODUCTS scope with no product -> MSG02")
    void utcid12() {
        assertBusiness(() -> requestService.create(owner, body(2, ScopeType.PRODUCTS, null, null, null)),
                HttpStatus.BAD_REQUEST, "MSG02");
        assertNotRequested();
    }

    @Test
    @DisplayName("UTCID13 A: product 99999 does not exist -> MSG242")
    void utcid13() {
        assertBusiness(() -> requestService.create(owner,
                body(2, ScopeType.PRODUCTS, null, null, List.of(P1, 99999L))), HttpStatus.BAD_REQUEST, "MSG242");
        assertNotRequested();
    }

    @Test
    @DisplayName("UTCID14 A: category 9 is INACTIVE -> MSG269")
    void utcid14() {
        assertBusiness(() -> requestService.create(owner, body(2, ScopeType.CATEGORY, null, 9L, null)),
                HttpStatus.BAD_REQUEST, "MSG269");
        assertNotRequested();
    }
}
