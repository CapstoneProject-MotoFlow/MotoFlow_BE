package fu.hl.g64.motorflow.stockcount.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.dto.LotAdjustPosting;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository.CountedLot;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.StockCountApproveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet StockCount.approve: StockCountServiceImpl.approve (UC-95), UTCID01-11. */
class StockCountApproveUnitTest extends StockCountUnitTestBase {

    private static final long LOT_A = 501, LOT_B = 502;
    private static final LocalDate COUNT_DATE = LocalDate.of(2026, 11, 17);

    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);
    private final AuthenticatedUser bmStore2 = auth(21, Role.BRANCH_MANAGER, 2L);

    private StockCount count;
    private final List<CountedLot> heldLots = new ArrayList<>();

    /** Count 1 of store 2 AWAITING_APPROVAL, counted on 17/11/2026, period 11/2026 OPEN. */
    @BeforeEach
    void defaultData() {
        count = registerCount(StockCount.create(2L, 11L, null, CountType.CYCLE, COUNT_DATE, ScopeType.WHOLE, null,
                null, 21L, NOW));
        count.startCounting(NOW);
        count.confirm(21L, true, NOW);
        when(lotBalances.findCountedLots(eq(2L), anyCollection())).thenAnswer(inv -> heldLots);
    }

    /** A counted line of count 1. */
    private StockCountLine counted(long id, long productId, Long lotId, int book, int bookDamaged, int countedQty,
                                   int damaged) {
        StockCountLine line = line(id, productId, lotId, book, bookDamaged);
        line.record(countedQty, damaged, 31L, NOW);
        line.explain("Lech kiem ke");
        return line;
    }

    /** a1: L1 P1 lot A book 4, counted 3; lot A holds AVAILABLE 4. */
    private void lotAShortByOne() {
        counted(1, P1, LOT_A, 4, 0, 3, 0);
        heldLots.add(countedLot(P1, LOT_A, 4, 0));
    }

    private static StockCountApproveRequest body(String reason) {
        return new StockCountApproveRequest(reason);
    }

    /** The one adjustment of a run: approve posts its lot steps as one adjustLotsAll call. */
    private LotAdjustPosting adjusted() {
        ArgumentCaptor<List<LotAdjustPosting>> posted = listCaptor();
        verify(posting).adjustLotsAll(posted.capture());
        assertThat(posted.getValue()).hasSize(1);
        return posted.getValue().getFirst();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private void assertApproved() {
        assertThat(count.getStatus()).isEqualTo(StockCountStatus.COMPLETED);
        assertThat(count.getApprovedBy()).isEqualTo(1L);
        verify(notifications).notifyStoreManagers(eq(2L), eq("STOCK_COUNT_APPROVED"), anyString(), anyString(),
                eq(1L));
    }

    private void assertNotApproved() {
        assertThat(count.getStatus()).isNotEqualTo(StockCountStatus.COMPLETED);
        verify(notifications, never()).notifyStoreManagers(anyLong(), anyString(), anyString(), anyString(),
                anyLong());
    }

    private void assertNothingPosted() {
        verify(posting, never()).adjustLotsAll(any());
        verify(posting, never()).receiveNewLotsAll(any());
    }

    @Test
    @DisplayName("UTCID01 N: Owner approves; lot A is adjusted -1 AVAILABLE on the count date")
    void utcid01() {
        lotAShortByOne();
        assertThat(service.approve(owner, 1, body("Dong y"))).isSameAs(detail);
        assertApproved();
        assertThat(count.getDecisionReason()).isEqualTo("Dong y");
        LotAdjustPosting move = adjusted();
        assertThat(move.lotId()).isEqualTo(LOT_A);
        assertThat(move.status()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(move.quantityChange()).isEqualTo(-1);
        assertThat(move.movementType()).isEqualTo(MovementType.COUNT_ADJUSTMENT);
        assertThat(move.periodId()).isEqualTo(11L);
        assertThat(move.occurredAt()).isEqualTo(COUNT_DATE.atTime(23, 59, 59));
    }

    @Test
    @DisplayName("UTCID02 N: surplus of P2 with no lot becomes a COUNT_SURPLUS lot at the average cost 50,000")
    void utcid02() {
        counted(2, P2, null, 0, 0, 2, 0);
        with(parts.get(P2), "movingAvgCost", new BigDecimal("50000"));
        service.approve(owner, 1, body(null));
        assertApproved();
        ArgumentCaptor<List<NewLotPosting>> posted = listCaptor();
        verify(posting).receiveNewLotsAll(posted.capture());
        assertThat(posted.getValue()).hasSize(1);
        NewLotPosting lot = posted.getValue().getFirst();
        assertThat(lot.productId()).isEqualTo(P2);
        assertThat(lot.sourceType()).isEqualTo(LotSourceType.COUNT_SURPLUS);
        assertThat(lot.purchaseUnitPrice()).isEqualTo(50_000L);
        assertThat(lot.quantities()).isEqualTo(Map.of(StockStatus.AVAILABLE, 2));
        verify(posting, never()).adjustLotsAll(any());
    }

    @Test
    @DisplayName("UTCID03 B: lot A counted 0 is adjusted -4, all it holds")
    void utcid03() {
        counted(1, P1, LOT_A, 4, 0, 0, 0);
        heldLots.add(countedLot(P1, LOT_A, 4, 0));
        service.approve(owner, 1, body(null));
        assertApproved();
        assertThat(adjusted().quantityChange()).isEqualTo(-4);
    }

    @Test
    @DisplayName("UTCID04 B: lot A now holds only 3 AVAILABLE, a -4 adjustment would go below 0 -> MSG241")
    void utcid04() {
        counted(1, P1, LOT_A, 4, 0, 0, 0);
        heldLots.add(countedLot(P1, LOT_A, 3, 0));
        assertBusiness(() -> service.approve(owner, 1, body(null)), HttpStatus.CONFLICT, "MSG241");
        assertNotApproved();
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID05 A: lot B now holds only 1 DAMAGED, a -2 damaged adjustment -> MSG267")
    void utcid05() {
        counted(3, P3, LOT_B, 2, 2, 0, 0);
        heldLots.add(countedLot(P3, LOT_B, 0, 1));
        assertBusiness(() -> service.approve(owner, 1, body(null)), HttpStatus.CONFLICT, "MSG267");
        assertNotApproved();
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID06 N: every line matches the book; nothing is posted")
    void utcid06() {
        counted(1, P1, LOT_A, 4, 0, 4, 0);
        heldLots.add(countedLot(P1, LOT_A, 4, 0));
        service.approve(owner, 1, body(null));
        assertApproved();
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID07 B: a blank decision reason is saved as null")
    void utcid07() {
        lotAShortByOne();
        service.approve(owner, 1, body("   "));
        assertApproved();
        assertThat(count.getDecisionReason()).isNull();
    }

    @Test
    @DisplayName("UTCID08 A: a Branch Manager cannot approve -> MSG05")
    void utcid08() {
        lotAShortByOne();
        assertBusiness(() -> service.approve(bmStore2, 1, body("Dong y")), HttpStatus.FORBIDDEN, "MSG05");
        assertNotApproved();
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID09 A: count 1 is still COUNTING -> MSG235")
    void utcid09() {
        count = registerCount(StockCount.create(2L, 11L, null, CountType.CYCLE, COUNT_DATE, ScopeType.WHOLE, null,
                null, 21L, NOW));
        count.startCounting(NOW);
        lotAShortByOne();
        assertBusiness(() -> service.approve(owner, 1, body("Dong y")), HttpStatus.CONFLICT, "MSG235");
        assertNotApproved();
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID10 A: the count's period is CLOSED -> MSG232")
    void utcid10() {
        periodStatus.put(11L, InventoryPeriodStatus.CLOSED);
        lotAShortByOne();
        assertBusiness(() -> service.approve(owner, 1, body("Dong y")), HttpStatus.CONFLICT, "MSG232");
        assertNotApproved();
        assertNothingPosted();
    }

    @Test
    @DisplayName("UTCID11 A: count 99999 does not exist -> MSG245")
    void utcid11() {
        lotAShortByOne();
        assertBusiness(() -> service.approve(owner, 99999, body("Dong y")), HttpStatus.NOT_FOUND, "MSG245");
        assertNotApproved();
        assertNothingPosted();
    }
}
