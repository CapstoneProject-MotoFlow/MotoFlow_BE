package fu.hl.g64.motorflow.stockcount.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.StockCountConfirmRequest;
import fu.hl.g64.motorflow.stockcount.dto.StockCountConfirmRequest.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet StockCount.confirm: StockCountServiceImpl.confirm (UC-94), UTCID01-18. */
class StockCountConfirmUnitTest extends StockCountUnitTestBase {

    private static final LocalDate YESTERDAY = LocalDate.of(2026, 11, 17);
    private static final LocalDate TODAY_DATE = LocalDate.of(2026, 11, 18);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 11, 19);

    private final AuthenticatedUser bmStore2 = auth(21, Role.BRANCH_MANAGER, 2L);
    private final AuthenticatedUser bmStore3 = auth(22, Role.BRANCH_MANAGER, 3L);
    private final AuthenticatedUser owner = auth(1, Role.OWNER, null);

    private StockCount count;
    private WarehouseTask task;

    /** 18/11/2026, period 11/2026 OPEN; count 1 of store 2 COUNTING, result submitted, book quantities taken. */
    @BeforeEach
    void defaultData() {
        count = counting(YESTERDAY);
        task = WarehouseTask.assign(TaskType.COUNTING, 1L, 2L, 31L, 21L, NOW);
        task.submitResult(NOW);
        when(tasks.findOpenForUpdate(TaskType.COUNTING, 1L)).thenAnswer(inv -> Optional.of(task));
    }

    private StockCount counting(LocalDate countDate) {
        StockCount created = registerCount(StockCount.create(2L, 11L, null, CountType.CYCLE, countDate,
                ScopeType.WHOLE, null, null, 21L, NOW));
        created.startCounting(NOW);
        return created;
    }

    /** L1: book 10, counted 10. */
    private StockCountLine l1() {
        StockCountLine line = line(1, P1, null, 10, 0);
        line.record(10, 0, 31L, NOW);
        return line;
    }

    /** L2: book 12, counted 10 (-2). */
    private StockCountLine l2() {
        StockCountLine line = line(2, P2, null, 12, 0);
        line.record(10, 0, 31L, NOW);
        return line;
    }

    /** L3: not counted. */
    private StockCountLine l3() {
        return line(3, P3, null, 5, 0);
    }

    private static StockCountConfirmRequest body(List<Long> removed, List<Reason> reasons) {
        return new StockCountConfirmRequest(removed, reasons);
    }

    private static StockCountConfirmRequest plain() {
        return body(List.of(), List.of());
    }

    private void assertNotConfirmed() {
        assertThat(count.getStatus()).isNotIn(StockCountStatus.COMPLETED, StockCountStatus.AWAITING_APPROVAL);
        verify(warehouseTasks, never()).confirmResult(any(), any(), anyLong());
        verify(lines, never()).deleteAllInBatch(any());
        verify(notifications, never()).notifyOwners(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("UTCID01 N: BM confirms a count with no difference; it is COMPLETED")
    void utcid01() {
        l1();
        assertThat(service.confirm(bmStore2, 1, plain())).isSameAs(detail);
        assertThat(count.getStatus()).isEqualTo(StockCountStatus.COMPLETED);
        assertThat(count.getConfirmedBy()).isEqualTo(21L);
        verify(warehouseTasks).confirmResult(bmStore2, TaskType.COUNTING, 1L);
        verify(notifications, never()).notifyOwners(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("UTCID02 B: count date is today (18/11/2026)")
    void utcid02() {
        count = counting(TODAY_DATE);
        l1();
        service.confirm(bmStore2, 1, plain());
        assertThat(count.getStatus()).isEqualTo(StockCountStatus.COMPLETED);
    }

    @Test
    @DisplayName("UTCID03 N: L2 differs by -2 with a reason; the count goes to the Owner")
    void utcid03() {
        l1();
        StockCountLine l2 = l2();
        service.confirm(bmStore2, 1, body(List.of(), List.of(new Reason(2L, "Vo 2 cai"))));
        assertThat(count.getStatus()).isEqualTo(StockCountStatus.AWAITING_APPROVAL);
        assertThat(l2.getReason()).isEqualTo("Vo 2 cai");
        verify(notifications).notifyOwners(eq("STOCK_COUNT_AWAITING_APPROVAL"), anyString(), anyString(), eq(1L));
    }

    @Test
    @DisplayName("UTCID04 N: BM removes uncounted L3; the count is COMPLETED")
    void utcid04() {
        l1();
        StockCountLine l3 = l3();
        service.confirm(bmStore2, 1, body(List.of(3L), List.of()));
        assertThat(count.getStatus()).isEqualTo(StockCountStatus.COMPLETED);
        verify(lines).deleteAllInBatch(List.of(l3));
    }

    @Test
    @DisplayName("UTCID05 A: count 99999 does not exist -> MSG245")
    void utcid05() {
        l1();
        assertBusiness(() -> service.confirm(bmStore2, 99999, plain()), HttpStatus.NOT_FOUND, "MSG245");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID06 A: BM of store 3 -> MSG05")
    void utcid06() {
        l1();
        assertBusiness(() -> service.confirm(bmStore3, 1, plain()), HttpStatus.FORBIDDEN, "MSG05");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID07 A: Owner does not confirm results -> MSG05")
    void utcid07() {
        l1();
        assertBusiness(() -> service.confirm(owner, 1, plain()), HttpStatus.FORBIDDEN, "MSG05");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID08 A: the counting task is still ASSIGNED -> MSG235")
    void utcid08() {
        task = WarehouseTask.assign(TaskType.COUNTING, 1L, 2L, 31L, 21L, NOW);
        l1();
        assertBusiness(() -> service.confirm(bmStore2, 1, plain()), HttpStatus.CONFLICT, "MSG235");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID09 A: count 1 is still DRAFT -> MSG235")
    void utcid09() {
        count = registerCount(StockCount.create(2L, 11L, null, CountType.CYCLE, YESTERDAY, ScopeType.WHOLE, null,
                null, 21L, NOW));
        l1();
        assertBusiness(() -> service.confirm(bmStore2, 1, plain()), HttpStatus.CONFLICT, "MSG235");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID10 B: count date is tomorrow (19/11/2026) -> MSG247")
    void utcid10() {
        count = counting(TOMORROW);
        l1();
        assertBusiness(() -> service.confirm(bmStore2, 1, plain()), HttpStatus.CONFLICT, "MSG247");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID11 A: L2 differs with no reason -> MSG236")
    void utcid11() {
        l1();
        l2();
        assertBusiness(() -> service.confirm(bmStore2, 1, plain()), HttpStatus.CONFLICT, "MSG236");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID12 A: L2's reason is blank -> MSG236")
    void utcid12() {
        l1();
        l2();
        assertBusiness(() -> service.confirm(bmStore2, 1, body(List.of(), List.of(new Reason(2L, "   ")))),
                HttpStatus.CONFLICT, "MSG236");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID13 A: removing counted line L1 -> MSG236")
    void utcid13() {
        l1();
        assertBusiness(() -> service.confirm(bmStore2, 1, body(List.of(1L), List.of())), HttpStatus.CONFLICT,
                "MSG236");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID14 A: removing L9 of another count -> MSG236")
    void utcid14() {
        l1();
        assertBusiness(() -> service.confirm(bmStore2, 1, body(List.of(9L), List.of())), HttpStatus.CONFLICT,
                "MSG236");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID15 A: uncounted L3 is kept -> MSG236")
    void utcid15() {
        l1();
        l3();
        assertBusiness(() -> service.confirm(bmStore2, 1, plain()), HttpStatus.CONFLICT, "MSG236");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID16 B: removing the only line L3 leaves an empty count -> MSG236")
    void utcid16() {
        l3();
        assertBusiness(() -> service.confirm(bmStore2, 1, body(List.of(3L), List.of())), HttpStatus.CONFLICT,
                "MSG236");
        assertNotConfirmed();
    }

    @Test
    @DisplayName("UTCID17 A: the count went to the Owner before, so no line may be removed -> MSG243")
    void utcid17() {
        with(count, "confirmedAt", NOW.minusDays(1));
        l1();
        l3();
        assertBusiness(() -> service.confirm(bmStore2, 1, body(List.of(3L), List.of())), HttpStatus.CONFLICT,
                "MSG243");
        verify(warehouseTasks, never()).confirmResult(any(), any(), anyLong());
        verify(lines, never()).deleteAllInBatch(any());
    }

    @Test
    @DisplayName("UTCID18 A: the count's period is CLOSED -> MSG232")
    void utcid18() {
        periodStatus.put(11L, InventoryPeriodStatus.CLOSED);
        l1();
        assertBusiness(() -> service.confirm(bmStore2, 1, plain()), HttpStatus.CONFLICT, "MSG232");
        assertNotConfirmed();
    }
}
