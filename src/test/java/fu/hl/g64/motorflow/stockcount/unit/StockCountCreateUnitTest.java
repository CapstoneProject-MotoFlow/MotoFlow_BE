package fu.hl.g64.motorflow.stockcount.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequest;
import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.StockCountCreateRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.TODAY;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet StockCount.create: StockCountServiceImpl.create (UC-90), UTCID01-23. */
class StockCountCreateUnitTest extends StockCountUnitTestBase {

    private final AuthenticatedUser bmStore2 = auth(21, Role.BRANCH_MANAGER, 2L);
    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    private final AuthenticatedUser staffStore2 = auth(31, Role.STAFF, 2L);

    private static final LocalDate END_OF_MONTH = LocalDate.of(2026, 11, 30);
    private static final LocalDate NEXT_MONTH = LocalDate.of(2026, 12, 1);
    private static final LocalDate CLOSED_MONTH = LocalDate.of(2026, 10, 15);

    private boolean unfinishedCount;

    /**
     * 18/11/2026, period 11/2026 OPEN, 10/2026 CLOSED; store 2 has area "Ke A", P1 and P2 in stock, category 3 in
     * stock, category 4 with no stock, category 9 INACTIVE; store 2 has no unfinished count; store 2 has stock moves.
     */
    @BeforeEach
    void defaultData() {
        unfinishedCount = false;
        when(countSheets.partsInScope(eq(2L), any(), any(), any(), anyList())).thenReturn(List.of(P1));
        when(lines.findPartsOnCounts(eq(2L), any(), any())).thenAnswer(inv -> unfinishedCount
                ? List.<Object[]>of(new Object[] {P1, 5L}) : List.of());
        when(counts.save(any())).thenAnswer(inv -> withId(inv.<StockCount>getArgument(0), 1));
        when(countSheets.buildLines(any(), anyList())).thenAnswer(inv -> {
            StockCount count = inv.getArgument(0);
            Long category = count.getPartCategoryId();
            return category != null && category == 4 ? List.of() : List.of(StockCountLine.create(1L, P1, null, null));
        });
        when(movements.existsByStoreId(2L)).thenReturn(true);
    }

    private static StockCountCreateRequest body(CountType type, LocalDate date, ScopeType scope, String note,
                                                Long categoryId, List<Long> productIds, Long requestId) {
        return new StockCountCreateRequest(type, date, scope, note, productIds, categoryId, requestId);
    }

    private static StockCountCreateRequest cycle(ScopeType scope, String note, Long categoryId, List<Long> productIds) {
        return body(CountType.CYCLE, TODAY, scope, note, categoryId, productIds, null);
    }

    private static StockCountCreateRequest whole() {
        return cycle(ScopeType.WHOLE, null, null, null);
    }

    private StockCount assertCreated(StockCountDetailResponse result) {
        assertThat(result).isSameAs(detail);
        ArgumentCaptor<StockCount> saved = ArgumentCaptor.forClass(StockCount.class);
        verify(counts).save(saved.capture());
        StockCount count = saved.getValue();
        assertThat(count.getStatus()).isEqualTo(StockCountStatus.DRAFT);
        assertThat(count.getStoreId()).isEqualTo(2L);
        assertThat(count.getInventoryPeriodId()).isEqualTo(11L);
        assertThat(count.getCreatedBy()).isEqualTo(21L);
        verify(countSheets).buildLines(eq(count), anyList());
        return count;
    }

    private void assertNotCreated() {
        verify(counts, never()).save(any());
    }

    @Test
    @DisplayName("UTCID01 N: BM creates a whole-warehouse cycle count")
    void utcid01() {
        StockCount count = assertCreated(service.create(bmStore2, whole()));
        assertThat(count.getScopeType()).isEqualTo(ScopeType.WHOLE);
        assertThat(count.getStockCountRequestId()).isNull();
    }

    @Test
    @DisplayName("UTCID02 N: BM creates a count of products [P1, P2]")
    void utcid02() {
        assertCreated(service.create(bmStore2, cycle(ScopeType.PRODUCTS, null, null, List.of(P1, P2))));
        verify(countSheets).buildLines(any(), eq(List.of(P1, P2)));
    }

    @Test
    @DisplayName("UTCID03 N: BM creates a count of area \"Ke A\"")
    void utcid03() {
        StockCount count = assertCreated(service.create(bmStore2, cycle(ScopeType.AREA, "Ke A", null, null)));
        assertThat(count.getScopeNote()).isEqualTo("Ke A");
    }

    @Test
    @DisplayName("UTCID04 N: BM creates a count of category 3")
    void utcid04() {
        StockCount count = assertCreated(service.create(bmStore2, cycle(ScopeType.CATEGORY, null, 3L, null)));
        assertThat(count.getPartCategoryId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("UTCID05 N: BM answers pending request 1 with the same scope; the request becomes DONE")
    void utcid05() {
        StockCountRequest request = countRequest(1, 2, ScopeType.PRODUCTS, List.of(P1, P2));
        StockCount count = assertCreated(service.create(bmStore2,
                body(CountType.CYCLE, TODAY, ScopeType.PRODUCTS, null, null, List.of(P1, P2), 1L)));
        assertThat(count.getStockCountRequestId()).isEqualTo(1L);
        assertThat(request.getStatus()).isEqualTo(StockCountRequestStatus.DONE);
    }

    @Test
    @DisplayName("UTCID06 B: count date is the last day of the month (30/11/2026)")
    void utcid06() {
        StockCount count = assertCreated(service.create(bmStore2,
                body(CountType.CYCLE, END_OF_MONTH, ScopeType.WHOLE, null, null, null, null)));
        assertThat(count.getCountDate()).isEqualTo(END_OF_MONTH);
    }

    @Test
    @DisplayName("UTCID07 B: count date is in next month (01/12/2026) -> MSG232")
    void utcid07() {
        assertBusiness(() -> service.create(bmStore2,
                        body(CountType.CYCLE, NEXT_MONTH, ScopeType.WHOLE, null, null, null, null)),
                HttpStatus.CONFLICT, "MSG232");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID08 A: opening count of a warehouse that already has stock moves -> MSG244")
    void utcid08() {
        assertBusiness(() -> service.create(bmStore2,
                        body(CountType.OPENING, TODAY, ScopeType.WHOLE, null, null, null, null)),
                HttpStatus.CONFLICT, "MSG244");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID09 A: Owner has no warehouse -> MSG05")
    void utcid09() {
        assertBusiness(() -> service.create(owner, whole()), HttpStatus.FORBIDDEN, "MSG05");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID10 A: Staff cannot create a count -> MSG05")
    void utcid10() {
        assertBusiness(() -> service.create(staffStore2, whole()), HttpStatus.FORBIDDEN, "MSG05");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID11 A: P1 is already on unfinished count #5 of store 2 -> MSG230")
    void utcid11() {
        unfinishedCount = true;
        assertBusiness(() -> service.create(bmStore2, whole()), HttpStatus.CONFLICT, "MSG230");
        verify(lines).findPartsOnCounts(eq(2L), eq(List.of(StockCountStatus.DRAFT, StockCountStatus.COUNTING,
                StockCountStatus.AWAITING_APPROVAL)), eq(List.of(P1)));
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID11b A: P1 on count #5 and P2 on count #7 -> MSG230 names only P1 and #5")
    void utcid11b() {
        when(countSheets.partsInScope(eq(2L), any(), any(), any(), anyList())).thenReturn(List.of(P1, P2));
        when(lines.findPartsOnCounts(eq(2L), any(), any()))
                .thenReturn(List.of(new Object[] {P1, 5L}, new Object[] {P2, 7L}));
        BusinessException refused = catchThrowableOfType(BusinessException.class,
                () -> service.create(bmStore2, whole()));
        assertThat(refused.getCode()).isEqualTo("MSG230");
        assertThat(refused.getArgs()).containsExactly("P1", "#5");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID12 A: count date is in closed period 10/2026 -> MSG232")
    void utcid12() {
        assertBusiness(() -> service.create(bmStore2,
                        body(CountType.CYCLE, CLOSED_MONTH, ScopeType.WHOLE, null, null, null, null)),
                HttpStatus.CONFLICT, "MSG232");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID13 A: WHOLE scope with a product list -> MSG02")
    void utcid13() {
        assertBusiness(() -> service.create(bmStore2, cycle(ScopeType.WHOLE, null, null, List.of(P1))),
                HttpStatus.BAD_REQUEST, "MSG02");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID14 A: area \"Ke Z\" is not in store 2 -> MSG02")
    void utcid14() {
        assertBusiness(() -> service.create(bmStore2, cycle(ScopeType.AREA, "Ke Z", null, null)),
                HttpStatus.BAD_REQUEST, "MSG02");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID15 A: product list repeats P1 -> MSG02")
    void utcid15() {
        assertBusiness(() -> service.create(bmStore2, cycle(ScopeType.PRODUCTS, null, null, List.of(P1, P1))),
                HttpStatus.BAD_REQUEST, "MSG02");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID16 B: PRODUCTS scope with no product -> MSG02")
    void utcid16() {
        assertBusiness(() -> service.create(bmStore2, cycle(ScopeType.PRODUCTS, null, null, null)),
                HttpStatus.BAD_REQUEST, "MSG02");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID17 A: product 99999 does not exist -> MSG242")
    void utcid17() {
        assertBusiness(() -> service.create(bmStore2, cycle(ScopeType.PRODUCTS, null, null, List.of(P1, 99999L))),
                HttpStatus.BAD_REQUEST, "MSG242");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID18 A: category 9 is INACTIVE -> MSG269")
    void utcid18() {
        assertBusiness(() -> service.create(bmStore2, cycle(ScopeType.CATEGORY, null, 9L, null)),
                HttpStatus.BAD_REQUEST, "MSG269");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID19 A: category 4 has no stock, so the count has no line -> MSG268")
    void utcid19() {
        assertBusiness(() -> service.create(bmStore2, cycle(ScopeType.CATEGORY, null, 4L, null)),
                HttpStatus.BAD_REQUEST, "MSG268");
    }

    @Test
    @DisplayName("UTCID20 A: request 2 is already DONE -> MSG233")
    void utcid20() {
        countRequest(2, 2, ScopeType.PRODUCTS, List.of(P1, P2)).markDone();
        assertBusiness(() -> service.create(bmStore2,
                        body(CountType.CYCLE, TODAY, ScopeType.PRODUCTS, null, null, List.of(P1, P2), 2L)),
                HttpStatus.CONFLICT, "MSG233");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID21 A: products [P1] differ from request 1's [P1, P2] -> MSG234")
    void utcid21() {
        StockCountRequest request = countRequest(1, 2, ScopeType.PRODUCTS, List.of(P1, P2));
        assertBusiness(() -> service.create(bmStore2,
                        body(CountType.CYCLE, TODAY, ScopeType.PRODUCTS, null, null, List.of(P1), 1L)),
                HttpStatus.CONFLICT, "MSG234");
        assertNotCreated();
        assertThat(request.isPending()).isTrue();
    }

    @Test
    @DisplayName("UTCID22 A: request 99999 does not exist -> MSG246")
    void utcid22() {
        assertBusiness(() -> service.create(bmStore2,
                        body(CountType.CYCLE, TODAY, ScopeType.WHOLE, null, null, null, 99999L)),
                HttpStatus.NOT_FOUND, "MSG246");
        assertNotCreated();
    }

    @Test
    @DisplayName("UTCID23 A: request 3 belongs to store 3 -> MSG05")
    void utcid23() {
        countRequest(3, 3, ScopeType.WHOLE, List.of());
        assertBusiness(() -> service.create(bmStore2,
                        body(CountType.CYCLE, TODAY, ScopeType.WHOLE, null, null, null, 3L)),
                HttpStatus.FORBIDDEN, "MSG05");
        assertNotCreated();
    }
}
