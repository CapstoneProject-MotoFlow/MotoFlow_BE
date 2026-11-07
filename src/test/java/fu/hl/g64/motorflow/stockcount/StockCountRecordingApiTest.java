package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-93. */
class StockCountRecordingApiTest extends StockCountTestData {

    private static Map<String, Object> line(long lineId, Integer qty) {
        Map<String, Object> m = new HashMap<>();
        m.put("lineId", lineId);
        m.put("countedQty", qty);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> lines(Map<String, Object>... lines) {
        return Map.of("lines", Arrays.asList(lines));
    }

    private ResultActions refused(ResultActions result, int httpStatus, String code) throws Exception {
        return result.andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code));
    }

    /** Draft count of the whole warehouse with the four lines of the fixture, given to {@link #staff}. */
    private long draft() {
        return count(StockCountStatus.DRAFT).line(oilLine(0, null)).line(filterLotALine(0, null))
                .line(filterLotBLine(0, null)).line(filterPlainLine(0, null))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();
    }

    private long counting() {
        return count(StockCountStatus.COUNTING).line(oilLine(12, null)).line(filterLotALine(4, null))
                .line(filterLotBLine(3, null)).line(filterPlainLine(2, null))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();
    }

    /** [book_qty, counted_qty, counted_by, previous_book_qty, previous_counted_qty] */
    private List<Object> row(long lineId) {
        Map<String, Object> r = jdbc.queryForMap("SELECT book_qty, counted_qty, counted_by, previous_book_qty, "
                + "previous_counted_qty FROM stock_count_line WHERE id = ?", lineId);
        return Arrays.asList(r.get("book_qty"), r.get("counted_qty"), r.get("counted_by"), r.get("previous_book_qty"),
                r.get("previous_counted_qty"));
    }

    private List<Object> bookQtys(long count) {
        return new ArrayList<>(lineIds(count).stream().map(l -> row(l).get(0)).toList());
    }

    private Object startedAt(long count) {
        return jdbc.queryForObject("SELECT counting_started_at FROM stock_count WHERE id = ?", Object.class, count);
    }

    @Test
    void startTakesBookQuantitiesPerLotAndForTheLotsWithoutExpiry() throws Exception {
        long count = draft();
        postAs(staffToken, "/api/v1/stock-counts/" + count + "/start", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COUNTING"));

        assertThat(countStatus(count)).isEqualTo("COUNTING");
        assertThat(startedAt(count)).isNotNull();
        // oil: both lots (5 + 7); filter lot A 4, lot B 3, and only the lot without expiry (2) for the null line
        assertThat(bookQtys(count)).containsExactly(12, 4, 3, 2);
    }

    @Test
    void aSaleAfterStartingDoesNotChangeTheBookQuantity() throws Exception {
        long count = draft();
        postAs(staffToken, "/api/v1/stock-counts/" + count + "/start", null).andExpect(status().isOk());
        sellFromLot(filterLotA, 3);
        sellFromLot(oilLot1, 5);

        postAs(staffToken, "/api/v1/stock-counts/" + count + "/start", null).andExpect(status().isOk());
        assertThat(bookQtys(count)).containsExactly(12, 4, 3, 2);
        assertThat(availableOfLot(filterLotA)).isEqualTo(1);
    }

    @Test
    void startingAgainWhileCountingKeepsEverything() throws Exception {
        long count = counting();
        Object before = startedAt(count);
        sellFromLot(oilLot2, 7);
        postAs(staffToken, "/api/v1/stock-counts/" + count + "/start", null).andExpect(status().isOk());
        assertThat(bookQtys(count)).containsExactly(12, 4, 3, 2);
        assertThat(startedAt(count)).isEqualTo(before);
    }

    @Test
    void onlyTheAssignedStaffMayStartRecordOrSubmit() throws Exception {
        long count = draft();
        String base = "/api/v1/stock-counts/" + count;
        refused(postAs(staff2Token, base + "/start", null), 403, "MSG238");
        refused(postAs(managerToken, base + "/start", null), 403, "MSG238");
        assertThat(countStatus(count)).isEqualTo("DRAFT");
        postAs(staffToken, base + "/start", null).andExpect(status().isOk());
        long first = lineIds(count).get(0);
        refused(putAs(staff2Token, base + "/lines", lines(line(first, 1))), 403, "MSG238");
        refused(postAs(staff2Token, base + "/submit", lines(line(first, 1))), 403, "MSG238");
        assertThat(row(first).get(1)).isNull();
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
    }

    @Test
    void stateGuards() throws Exception {
        long waiting = count(StockCountStatus.AWAITING_APPROVAL).line(oilLine(12, 12))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();
        refused(postAs(staffToken, "/api/v1/stock-counts/" + waiting + "/start", null), 409, "MSG235");

        long draft = draft();
        refused(putAs(staffToken, "/api/v1/stock-counts/" + draft + "/lines", lines(line(lineIds(draft).get(0), 1))),
                409, "MSG235");
        refused(postAs(staffToken, "/api/v1/stock-counts/" + draft + "/submit", Map.of()), 409, "MSG235");
        assertThat(countStatus(draft)).isEqualTo("DRAFT");
        assertThat(taskStatuses(draft)).containsExactly("ASSIGNED");
    }

    @Test
    void invalidLinesAreRefusedAndNothingIsSaved() throws Exception {
        long count = counting();
        long other = count(StockCountStatus.CANCELLED).line(oilLine(1, null)).insert();
        List<Long> ids = lineIds(count);
        String url = "/api/v1/stock-counts/" + count + "/lines";
        refused(putAs(staffToken, url, lines(line(ids.get(0), 5), line(ids.get(1), -1))), 400, "MSG237");
        refused(putAs(staffToken, url, lines(line(ids.get(0), 5), line(lineIds(other).get(0), 1))), 400, "MSG237");
        assertThat(row(ids.get(0)).get(1)).isNull();
        assertThat(row(lineIds(other).get(0)).get(1)).isNull();
    }

    @Test
    void savingADraftKeepsBlanksAndWhoCounted() throws Exception {
        long count = counting();
        List<Long> ids = lineIds(count);
        putAs(staffToken, "/api/v1/stock-counts/" + count + "/lines",
                lines(line(ids.get(0), 11), line(ids.get(1), 4), line(ids.get(2), null)))
                .andExpect(status().isOk());
        assertThat(row(ids.get(0)).subList(1, 3)).containsExactly(11, staff.getId());
        assertThat(row(ids.get(1)).get(1)).isEqualTo(4);
        assertThat(row(ids.get(2)).get(1)).isNull();
        assertThat(row(ids.get(3)).get(1)).isNull();

        putAs(staffToken, "/api/v1/stock-counts/" + count + "/lines", lines(line(ids.get(0), null)))
                .andExpect(status().isOk());
        assertThat(row(ids.get(0)).get(1)).isNull();
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
    }

    @Test
    void submitSavesLinesSendsTheResultAndTouchesNoStock() throws Exception {
        long count = counting();
        List<Long> ids = lineIds(count);
        putAs(staffToken, "/api/v1/stock-counts/" + count + "/lines", lines(line(ids.get(0), 10)))
                .andExpect(status().isOk());
        int oilBefore = availableOf(store, oil);

        postAs(staffToken, "/api/v1/stock-counts/" + count + "/submit",
                lines(line(ids.get(1), 3), line(ids.get(2), 3), line(ids.get(3), 2)))
                .andExpect(status().isOk());

        assertThat(countStatus(count)).isEqualTo("COUNTING");
        assertThat(taskStatuses(count)).containsExactly("RESULT_SUBMITTED");
        assertThat(row(ids.get(0)).get(1)).isEqualTo(10);
        assertThat(row(ids.get(1)).get(1)).isEqualTo(3);
        assertThat(movementsOf(count)).isEmpty();
        assertThat(availableOf(store, oil)).isEqualTo(oilBefore);
        assertThat(notificationsOf(manager)).contains(Arrays.asList("STOCK_COUNT_SUBMITTED", "STOCK_COUNT", count));
        assertThat(notificationsOf(manager2)).contains(Arrays.asList("STOCK_COUNT_SUBMITTED", "STOCK_COUNT", count));
        assertThat(notificationsOf(staff)).isEmpty();

        // once sent, the counter can neither change the lines nor send again
        refused(putAs(staffToken, "/api/v1/stock-counts/" + count + "/lines", lines(line(ids.get(0), 1))), 409,
                "MSG235");
        refused(postAs(staffToken, "/api/v1/stock-counts/" + count + "/submit", Map.of()), 409, "MSG235");
        assertThat(row(ids.get(0)).get(1)).isEqualTo(10);
    }

    @Test
    void submitWithUncountedLinesIsAllowed() throws Exception {
        long count = counting();
        postAs(staffToken, "/api/v1/stock-counts/" + count + "/submit", Map.of()).andExpect(status().isOk());
        assertThat(taskStatuses(count)).containsExactly("RESULT_SUBMITTED");
    }

    @Test
    void recountKeepsThePreviousQuantitiesAndOnlyThoseLinesAreEditable() throws Exception {
        long count = count(StockCountStatus.COUNTING).awaitingRestart()
                .line(oilLine(10, 9).recount("Đếm lại"))
                .line(filterLotALine(4, 4)).line(filterLotBLine(3, 3)).line(filterPlainLine(2, 2))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();
        List<Long> ids = lineIds(count);
        String base = "/api/v1/stock-counts/" + count;

        // cannot record until the book quantities are taken again
        refused(putAs(staffToken, base + "/lines", lines(line(ids.get(0), 1))), 409, "MSG235");

        postAs(staffToken, base + "/start", null).andExpect(status().isOk());
        assertThat(row(ids.get(0))).containsExactly(12, null, null, 10, 9);
        assertThat(row(ids.get(1)).subList(0, 2)).containsExactly(4, 4);
        assertThat(row(ids.get(3)).subList(0, 2)).containsExactly(2, 2);
        assertThat(startedAt(count)).isNotNull();

        refused(putAs(staffToken, base + "/lines", lines(line(ids.get(1), 4))), 400, "MSG237");
        putAs(staffToken, base + "/lines", lines(line(ids.get(0), 12))).andExpect(status().isOk());
        assertThat(row(ids.get(0))).containsExactly(12, 12, staff.getId(), 10, 9);
    }
}
