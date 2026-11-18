package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fu.hl.g64.motorflow.support.Report51Support.NO_TOKEN;
import static fu.hl.g64.motorflow.support.Report51Support.as;
import static fu.hl.g64.motorflow.support.Report51Support.expectError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-93: StockCountService.submit, UTC033 to UTC043. Count #1 is of {@link #store} (store #2) with line L1 (part
 * {@link #filter}, lot A, book 4) and L2 (part {@link #oil}, book 10); Staff S1 is {@link #staff}, S2 is {@link #staff2}.
 */
@Tag("report51")
class SubmitStockCountUnitTest extends StockCountTestData {

    private long l1;
    private long l2;

    private static Map<String, Object> line(long lineId, int qty) {
        Map<String, Object> m = new HashMap<>();
        m.put("lineId", lineId);
        m.put("countedQty", qty);
        return m;
    }

    @SafeVarargs
    private String lines(Map<String, Object>... lines) {
        return json(Map.of("lines", Arrays.asList(lines)));
    }

    private String url(long count) {
        return "/api/v1/stock-counts/" + count + "/submit";
    }

    /** COUNTING with book quantities taken and S1 holding the task in {@code task}; L1 and L2 as given. */
    private long counting(WarehouseTaskStatus task, SeedLine first, SeedLine second) {
        long count = count(StockCountStatus.COUNTING).line(first).line(second).task(staff, task).insert();
        setLineIds(count);
        return count;
    }

    private long counting() {
        return counting(WarehouseTaskStatus.ASSIGNED, filterLotALine(4, null), oilLine(10, null));
    }

    private void setLineIds(long count) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM stock_count_line WHERE stock_count_id = ? ORDER BY id",
                Long.class, count);
        l1 = ids.get(0);
        l2 = ids.get(1);
    }

    /** [counted_qty, counted_by] of a line. */
    private List<Object> counted(long lineId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT counted_qty, counted_by FROM stock_count_line WHERE id = ?",
                lineId);
        return Arrays.asList(row.get("counted_qty"), row.get("counted_by"));
    }

    private List<Object> notCounted() {
        return Arrays.asList(null, null);
    }

    @Test
    @DisplayName("UTC033 Normal: S1 submits both lines")
    void utc033() throws Exception {
        long count = counting();

        mvc.perform(as(staffToken, post(url(count)), lines(line(l1, 4), line(l2, 10))))
                .andExpect(status().isOk());

        assertThat(counted(l1)).containsExactly(4, staff.getId());
        assertThat(counted(l2)).containsExactly(10, staff.getId());
        assertThat(taskStatuses(count)).containsExactly("RESULT_SUBMITTED");
        assertThat(countStatus(count)).isEqualTo("COUNTING");
        assertThat(notificationsOf(manager)).containsExactly(Arrays.asList("STOCK_COUNT_SUBMITTED", "STOCK_COUNT", count));
    }

    @Test
    @DisplayName("UTC034 Normal: submitting without a body keeps the quantities saved as a draft")
    void utc034() throws Exception {
        long count = counting(WarehouseTaskStatus.ASSIGNED, filterLotALine(4, 4), oilLine(10, 9));

        mvc.perform(as(staffToken, post(url(count))))
                .andExpect(status().isOk());

        assertThat(counted(l1)).containsExactly(4, staff.getId());
        assertThat(counted(l2)).containsExactly(9, staff.getId());
        assertThat(taskStatuses(count)).containsExactly("RESULT_SUBMITTED");
    }

    @Test
    @DisplayName("UTC035 Boundary: a counted quantity of 0 is accepted")
    void utc035() throws Exception {
        long count = counting();

        mvc.perform(as(staffToken, post(url(count)), lines(line(l1, 0), line(l2, 10))))
                .andExpect(status().isOk());

        assertThat(counted(l1)).containsExactly(0, staff.getId());
        assertThat(counted(l2)).containsExactly(10, staff.getId());
        assertThat(taskStatuses(count)).containsExactly("RESULT_SUBMITTED");
    }

    @Test
    @DisplayName("UTC036 Boundary: a negative counted quantity is refused and nothing is recorded")
    void utc036() throws Exception {
        long count = counting();

        mvc.perform(as(staffToken, post(url(count)), lines(line(l1, -1))))
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG237"));

        assertThat(counted(l1)).isEqualTo(notCounted());
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
        assertThat(notificationsOf(manager)).isEmpty();
    }

    @Test
    @DisplayName("UTC037 Abnormal: a line of another count is refused")
    void utc037() throws Exception {
        long count = counting();
        long other = count(StockCountStatus.CANCELLED).line(oilLine(1, null)).insert();
        long l9 = lineIds(other).get(0);

        mvc.perform(as(staffToken, post(url(count)), lines(line(l9, 3))))
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG237"));

        assertThat(counted(l9)).isEqualTo(notCounted());
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
    }

    @Test
    @DisplayName("UTC038 Abnormal: only the lines returned for recount may change")
    void utc038() throws Exception {
        long count = counting(WarehouseTaskStatus.ASSIGNED, filterLotALine(4, null).recount("Dem lai").previously(4, 3),
                oilLine(10, 10));

        mvc.perform(as(staffToken, post(url(count)), lines(line(l2, 9))))
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG237"));

        assertThat(counted(l2)).containsExactly(10, staff.getId());
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
    }

    @Test
    @DisplayName("UTC039 Normal: the line returned for recount can be counted again")
    void utc039() throws Exception {
        long count = counting(WarehouseTaskStatus.ASSIGNED, filterLotALine(4, null).recount("Dem lai").previously(4, 3),
                oilLine(10, 10));

        mvc.perform(as(staffToken, post(url(count)), lines(line(l1, 3))))
                .andExpect(status().isOk());

        assertThat(counted(l1)).containsExactly(3, staff.getId());
        assertThat(counted(l2)).containsExactly(10, staff.getId());
        assertThat(taskStatuses(count)).containsExactly("RESULT_SUBMITTED");
    }

    @Test
    @DisplayName("UTC040 Abnormal: S1 already submitted")
    void utc040() throws Exception {
        long count = counting(WarehouseTaskStatus.RESULT_SUBMITTED, filterLotALine(4, 4), oilLine(10, 10));

        mvc.perform(as(staffToken, post(url(count)), lines(line(l1, 1), line(l2, 1))))
                .andExpect(expectError(HttpStatus.CONFLICT, "MSG235"));

        assertThat(counted(l1)).containsExactly(4, staff.getId());
        assertThat(counted(l2)).containsExactly(10, staff.getId());
        assertThat(taskStatuses(count)).containsExactly("RESULT_SUBMITTED");
    }

    @Test
    @DisplayName("UTC041 Abnormal: the count is still a draft")
    void utc041() throws Exception {
        long count = count(StockCountStatus.DRAFT).line(filterLotALine(0, null)).line(oilLine(0, null))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();
        setLineIds(count);

        mvc.perform(as(staffToken, post(url(count)), lines(line(l1, 4), line(l2, 10))))
                .andExpect(expectError(HttpStatus.CONFLICT, "MSG235"));

        assertThat(countStatus(count)).isEqualTo("DRAFT");
        assertThat(counted(l1)).isEqualTo(notCounted());
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
    }

    @Test
    @DisplayName("UTC042 Abnormal: Staff S2 is not assigned to the count")
    void utc042() throws Exception {
        long count = counting();

        mvc.perform(as(staff2Token, post(url(count)), lines(line(l1, 4), line(l2, 10))))
                .andExpect(expectError(HttpStatus.FORBIDDEN, "MSG238"));

        assertThat(counted(l1)).isEqualTo(notCounted());
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
        assertThat(notificationsOf(manager)).isEmpty();
    }

    @Test
    @DisplayName("UTC043 Abnormal: not signed in")
    void utc043() throws Exception {
        long count = counting();

        mvc.perform(as(NO_TOKEN, post(url(count)), lines(line(l1, 4), line(l2, 10))))
                .andExpect(expectError(HttpStatus.UNAUTHORIZED, "MSG06"));

        assertThat(counted(l1)).isEqualTo(notCounted());
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
    }
}
