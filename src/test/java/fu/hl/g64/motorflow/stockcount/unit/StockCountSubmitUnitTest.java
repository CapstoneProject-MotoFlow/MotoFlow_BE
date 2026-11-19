package fu.hl.g64.motorflow.stockcount.unit;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountLinesSaveRequest.Line;
import fu.hl.g64.motorflow.stockcount.dto.StockCountSubmitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.TODAY;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet StockCount.submit: StockCountServiceImpl.submit (UC-93), UTCID01-14. */
class StockCountSubmitUnitTest extends StockCountUnitTestBase {

    private static final long LOT_A = 501;

    private final AuthenticatedUser staffS1 = auth(31, Role.STAFF, 2L);
    private final AuthenticatedUser staffS2 = auth(32, Role.STAFF, 2L);
    private final AuthenticatedUser bmStore2 = auth(21, Role.BRANCH_MANAGER, 2L);

    private StockCount count;
    private WarehouseTask task;
    private StockCountLine l1;
    private StockCountLine l2;

    /** Count 1 of store 2 COUNTING, book quantities taken; L1 (P1 lot A, book 4), L2 (P2, book 10); S1 holds the task. */
    @BeforeEach
    void defaultData() {
        count = registerCount(StockCount.create(2L, 11L, null, CountType.CYCLE, TODAY, ScopeType.WHOLE, null, null,
                21L, NOW));
        count.startCounting(NOW);
        l1 = line(1, P1, LOT_A, 4, 0);
        l2 = line(2, P2, null, 10, 0);
        task = WarehouseTask.assign(TaskType.COUNTING, 1L, 2L, 31L, 21L, NOW);
        when(tasks.findHistory(TaskType.COUNTING, 1L)).thenAnswer(inv -> List.of(task));
    }

    private static StockCountSubmitRequest body(Line... lines) {
        return new StockCountSubmitRequest(List.of(lines));
    }

    private static Line line(long id, int counted) {
        return new Line(id, counted, null);
    }

    private void assertSubmitted(StockCountDetailResponse result) {
        assertThat(result).isSameAs(detail);
        verify(warehouseTasks).submitResult(staffS1, TaskType.COUNTING, 1L);
        verify(notifications).notifyStoreManagers(eq(2L), eq("STOCK_COUNT_SUBMITTED"), anyString(), anyString(),
                eq(1L));
    }

    private void assertNotSubmitted() {
        verify(warehouseTasks, never()).submitResult(any(), any(), anyLong());
        verify(notifications, never()).notifyStoreManagers(anyLong(), anyString(), anyString(), anyString(),
                anyLong());
    }

    @Test
    @DisplayName("UTCID01 N: S1 submits L1 = 4 and L2 = 10")
    void utcid01() {
        assertSubmitted(service.submit(staffS1, 1, body(line(1, 4), line(2, 10))));
        assertThat(l1.getCountedQty()).isEqualTo(4);
        assertThat(l2.getCountedQty()).isEqualTo(10);
        assertThat(l1.getCountedBy()).isEqualTo(31L);
    }

    @Test
    @DisplayName("UTCID02 N: S1 submits with no lines; the saved draft (L1 = 4, L2 = 9) is kept")
    void utcid02() {
        l1.record(4, 0, 31L, NOW);
        l2.record(9, 0, 31L, NOW);
        assertSubmitted(service.submit(staffS1, 1, new StockCountSubmitRequest(null)));
        assertThat(l1.getCountedQty()).isEqualTo(4);
        assertThat(l2.getCountedQty()).isEqualTo(9);
    }

    @Test
    @DisplayName("UTCID03 B: L1 counted 0")
    void utcid03() {
        assertSubmitted(service.submit(staffS1, 1, body(line(1, 0), line(2, 10))));
        assertThat(l1.getCountedQty()).isZero();
    }

    @Test
    @DisplayName("UTCID04 B: L1 counted -1 -> MSG237")
    void utcid04() {
        assertBusiness(() -> service.submit(staffS1, 1, body(line(1, -1))), HttpStatus.BAD_REQUEST, "MSG237");
        assertNotSubmitted();
        assertThat(l1.isCounted()).isFalse();
    }

    @Test
    @DisplayName("UTCID05 A: line L9 belongs to count 2 -> MSG237")
    void utcid05() {
        assertBusiness(() -> service.submit(staffS1, 1, body(line(9, 3))), HttpStatus.BAD_REQUEST, "MSG237");
        assertNotSubmitted();
    }

    @Test
    @DisplayName("UTCID06 A: BM returned only L1 for recount, S1 changes L2 -> MSG237")
    void utcid06() {
        l1.requestRecount("Dem lai");
        assertBusiness(() -> service.submit(staffS1, 1, body(line(2, 9))), HttpStatus.BAD_REQUEST, "MSG237");
        assertNotSubmitted();
    }

    @Test
    @DisplayName("UTCID07 N: BM returned L1 for recount, S1 recounts L1 = 3")
    void utcid07() {
        l1.requestRecount("Dem lai");
        assertSubmitted(service.submit(staffS1, 1, body(line(1, 3))));
        assertThat(l1.getCountedQty()).isEqualTo(3);
    }

    @Test
    @DisplayName("UTCID08 B: L1 counted 4 with damaged 4")
    void utcid08() {
        assertSubmitted(service.submit(staffS1, 1, body(new Line(1L, 4, 4))));
        assertThat(l1.getCountedQty()).isEqualTo(4);
        assertThat(l1.getDamagedQty()).isEqualTo(4);
    }

    @Test
    @DisplayName("UTCID09 B: L1 damaged 5 is more than counted 4 -> MSG266")
    void utcid09() {
        assertBusiness(() -> service.submit(staffS1, 1, body(new Line(1L, 4, 5))), HttpStatus.BAD_REQUEST, "MSG266");
        assertNotSubmitted();
    }

    @Test
    @DisplayName("UTCID10 A: S1's task is already RESULT_SUBMITTED -> MSG235")
    void utcid10() {
        task.submitResult(NOW);
        assertBusiness(() -> service.submit(staffS1, 1, body(line(1, 4), line(2, 10))), HttpStatus.CONFLICT,
                "MSG235");
        assertNotSubmitted();
    }

    @Test
    @DisplayName("UTCID11 A: count 1 is still DRAFT -> MSG235")
    void utcid11() {
        count = registerCount(StockCount.create(2L, 11L, null, CountType.CYCLE, TODAY, ScopeType.WHOLE, null, null,
                21L, NOW));
        assertBusiness(() -> service.submit(staffS1, 1, body(line(1, 4), line(2, 10))), HttpStatus.CONFLICT,
                "MSG235");
        assertNotSubmitted();
    }

    @Test
    @DisplayName("UTCID12 A: S2 was not given the counting task -> MSG238")
    void utcid12() {
        assertBusiness(() -> service.submit(staffS2, 1, body(line(1, 4), line(2, 10))), HttpStatus.FORBIDDEN,
                "MSG238");
        assertNotSubmitted();
    }

    @Test
    @DisplayName("UTCID13 A: BM of store 2 does not hold the counting task -> MSG238")
    void utcid13() {
        assertBusiness(() -> service.submit(bmStore2, 1, body(line(1, 4), line(2, 10))), HttpStatus.FORBIDDEN,
                "MSG238");
        assertNotSubmitted();
    }

    @Test
    @DisplayName("UTCID14 A: count 99999 does not exist -> MSG245")
    void utcid14() {
        assertBusiness(() -> service.submit(staffS1, 99999, body(line(1, 4), line(2, 10))), HttpStatus.NOT_FOUND,
                "MSG245");
        assertNotSubmitted();
    }
}
