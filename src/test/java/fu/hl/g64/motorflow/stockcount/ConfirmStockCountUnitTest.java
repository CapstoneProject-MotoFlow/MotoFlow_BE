package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static fu.hl.g64.motorflow.support.Report51Support.NO_TOKEN;
import static fu.hl.g64.motorflow.support.Report51Support.as;
import static fu.hl.g64.motorflow.support.Report51Support.expectError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Report 5.1, function ConfirmStockCount: StockCountService.confirm, POST /api/v1/stock-counts/{id}/confirm (UC-94). */
@Tag("report51")
class ConfirmStockCountUnitTest extends StockCountTestData {

    @Test
    @DisplayName("UTC006 Normal: result submitted with no difference completes the count")
    void utc006() throws Exception {
        long count = submittedWithoutDifference();

        confirm(managerToken, count, List.of(), List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(countStatus(count)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT confirmed_by FROM stock_count WHERE id = ?", Long.class, count))
                .isEqualTo(manager.getId());
        assertThat(taskStatuses(count)).containsExactly("DONE");
        assertThat(movementsOf(count)).isEmpty();
    }

    @Test
    @DisplayName("UTC007 Normal: a difference of -2 with a reason goes to the Owner for approval")
    void utc007() throws Exception {
        long count = count(StockCountStatus.COUNTING).line(oilLine(12, 10))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
        long line = lineIds(count).getFirst();

        confirm(managerToken, count, List.of(), List.of(reason(line, "Broken"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));

        assertThat(countStatus(count)).isEqualTo("AWAITING_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT reason FROM stock_count_line WHERE id = ?", String.class, line))
                .isEqualTo("Broken");
        assertThat(taskStatuses(count)).containsExactly("DONE");
        assertThat(movementsOf(count)).isEmpty();
    }

    @Test
    @DisplayName("UTC008 Abnormal: a line with a difference has no reason")
    void utc008() throws Exception {
        long count = count(StockCountStatus.COUNTING).line(oilLine(12, 10))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();

        confirm(managerToken, count, List.of(), List.of()).andExpect(expectError(HttpStatus.CONFLICT, "MSG236"));

        assertStillCounting(count, 1);
    }

    @Test
    @DisplayName("UTC009 Normal: an uncounted line is removed and the count completes")
    void utc009() throws Exception {
        long count = count(StockCountStatus.COUNTING).line(oilLine(12, 12)).line(filterLotALine(4, null))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
        List<Long> ids = lineIds(count);

        confirm(managerToken, count, List.of(ids.get(1)), List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(lineIds(count)).containsExactly(ids.get(0));
        assertThat(countStatus(count)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("UTC010 Abnormal: the Staff member has not submitted the result")
    void utc010() throws Exception {
        long count = count(StockCountStatus.COUNTING).line(oilLine(12, 12))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();

        confirm(managerToken, count, List.of(), List.of()).andExpect(expectError(HttpStatus.CONFLICT, "MSG235"));

        assertThat(countStatus(count)).isEqualTo("COUNTING");
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
    }

    @Test
    @DisplayName("UTC011 Boundary: count date is tomorrow")
    void utc011() throws Exception {
        long count = submittedOn(TODAY.plusDays(1));

        confirm(managerToken, count, List.of(), List.of()).andExpect(expectError(HttpStatus.CONFLICT, "MSG247"));

        assertStillCounting(count, 1);
    }

    @Test
    @DisplayName("UTC012 Boundary: count date is today")
    void utc012() throws Exception {
        long count = submittedOn(TODAY);

        confirm(managerToken, count, List.of(), List.of()).andExpect(status().isOk());

        assertThat(countStatus(count)).isEqualTo("COMPLETED");
        assertThat(taskStatuses(count)).containsExactly("DONE");
    }

    @Test
    @DisplayName("UTC013 Abnormal: removing a line after the count was already sent to the Owner")
    void utc013() throws Exception {
        long count = count(StockCountStatus.COUNTING).confirmedOnce().line(oilLine(12, 12))
                .line(filterLotALine(4, null)).task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();

        confirm(managerToken, count, List.of(lineIds(count).get(1)), List.of())
                .andExpect(expectError(HttpStatus.CONFLICT, "MSG243"));

        assertStillCounting(count, 2);
    }

    @Test
    @DisplayName("UTC014 Abnormal: Branch Manager of another store")
    void utc014() throws Exception {
        long count = submittedWithoutDifference();

        confirm(otherManagerToken, count, List.of(), List.of()).andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertStillCounting(count, 2);
    }

    @Test
    @DisplayName("UTC015 Abnormal: stock count 99999 does not exist")
    void utc015() throws Exception {
        long count = submittedWithoutDifference();

        confirm(managerToken, 99999, List.of(), List.of()).andExpect(expectError(HttpStatus.NOT_FOUND, "MSG245"));

        assertStillCounting(count, 2);
    }

    @Test
    @DisplayName("UTC016 Abnormal: not signed in")
    void utc016() throws Exception {
        long count = submittedWithoutDifference();

        confirm(NO_TOKEN, count, List.of(), List.of()).andExpect(expectError(HttpStatus.UNAUTHORIZED, "MSG06"));

        assertStillCounting(count, 2);
    }

    @Test
    @DisplayName("UTC017 Abnormal: Owner may not confirm a count")
    void utc017() throws Exception {
        long count = submittedWithoutDifference();

        confirm(ownerToken, count, List.of(), List.of()).andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertStillCounting(count, 2);
    }

    /** Oil 12 counted 12 and filter lot A 4 counted 4, result submitted by the Staff member. */
    private long submittedWithoutDifference() {
        return count(StockCountStatus.COUNTING).line(oilLine(12, 12)).line(filterLotALine(4, 4))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
    }

    /** Oil 12 counted 12, result submitted, count date {@code date} in the open period. */
    private long submittedOn(LocalDate date) {
        return count(StockCountStatus.COUNTING).on(date, period).line(oilLine(12, 12))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
    }

    private static Map<String, Object> reason(long lineId, String text) {
        return Map.of("lineId", lineId, "reason", text);
    }

    private ResultActions confirm(String token, long count, List<Long> removed, List<Map<String, Object>> reasons)
            throws Exception {
        return mvc.perform(as(token, post("/api/v1/stock-counts/" + count + "/confirm"),
                json(Map.of("removedLineIds", removed, "reasons", reasons))));
    }

    /** Nothing was confirmed: still counting with the result waiting, and all {@code lines} kept. */
    private void assertStillCounting(long count, int lines) {
        assertThat(countStatus(count)).isEqualTo("COUNTING");
        assertThat(taskStatuses(count)).containsExactly("RESULT_SUBMITTED");
        assertThat(lineIds(count)).hasSize(lines);
        assertThat(movementsOf(count)).isEmpty();
    }
}
