package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-94 Confirm Result and Request Recount. */
class StockCountConfirmationApiTest extends StockCountTestData {

    private static Map<String, Object> reason(long lineId, String text) {
        return Map.of("lineId", lineId, "reason", text);
    }

    private static Map<String, Object> confirmBody(List<Long> removed, List<Map<String, Object>> reasons) {
        return Map.of("removedLineIds", removed, "reasons", reasons);
    }

    private String url(long count, String action) {
        return "/api/v1/stock-counts/" + count + "/" + action;
    }

    /** Oil 12 counted 12 and filter lot A 4 counted 4, result sent by {@link #staff}. */
    private long submittedWithoutDifference() {
        return count(StockCountStatus.COUNTING).line(oilLine(12, 12)).line(filterLotALine(4, 4))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
    }

    /** Oil 12 counted 10 (difference), filter lot A 4 counted 4, result sent by {@link #staff}. */
    private long submittedWithDifference() {
        return count(StockCountStatus.COUNTING).line(oilLine(12, 10)).line(filterLotALine(4, 4))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
    }

    private void assertStillCounting(long count) {
        assertThat(countStatus(count)).isEqualTo("COUNTING");
        assertThat(taskStatuses(count)).containsExactly("RESULT_SUBMITTED");
    }

    // ---------------------------------------------------------------------------------------------- confirm

    @Test
    void aCountWithoutDifferenceIsCompletedAtOnceWithNoMovement() throws Exception {
        long count = submittedWithoutDifference();

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.confirmedBy").value(manager.getId()));

        assertThat(countStatus(count)).isEqualTo("COMPLETED");
        assertThat(movementsOf(count)).isEmpty();
        assertThat(taskStatuses(count)).containsExactly("DONE");
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NOT NULL FROM stock_count WHERE id = ?", Boolean.class,
                count)).isTrue();
        assertThat(notificationsOf(owner)).isEmpty();
        assertThat(availableOf(store, oil)).isEqualTo(12);
    }

    @Test
    void aCountWithDifferenceKeepsTheReasonAndWaitsForTheOwner() throws Exception {
        long count = submittedWithDifference();
        long oilLine = lineIds(count).getFirst();

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(), List.of(reason(oilLine, "  Rơi vỡ  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));

        assertThat(countStatus(count)).isEqualTo("AWAITING_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT reason FROM stock_count_line WHERE id = ?", String.class, oilLine))
                .isEqualTo("Rơi vỡ");
        assertThat(movementsOf(count)).isEmpty();
        assertThat(taskStatuses(count)).containsExactly("DONE");
        assertThat(notificationsOf(owner)).containsExactly(
                List.of("STOCK_COUNT_AWAITING_APPROVAL", "STOCK_COUNT", count));
    }

    @Test
    void aReasonForALineWithoutDifferenceIsIgnored() throws Exception {
        long count = submittedWithDifference();
        List<Long> ids = lineIds(count);

        postAs(managerToken, url(count, "confirm"),
                confirmBody(List.of(), List.of(reason(ids.get(0), "Rơi vỡ"), reason(ids.get(1), "Thừa lời"))))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT reason FROM stock_count_line WHERE id = ?", String.class, ids.get(1)))
                .isNull();
    }

    @Test
    void anOldReasonIsClearedWhenTheRecountRemovedTheDifference() throws Exception {
        long count = count(StockCountStatus.COUNTING).confirmedOnce().line(oilLine(12, 12).because("cũ"))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT reason FROM stock_count_line WHERE id = ?", String.class,
                lineIds(count).getFirst())).isNull();
    }

    @Test
    void anUncountedLineCanBeRemovedAndIsNeverFilledWithZero() throws Exception {
        long count = count(StockCountStatus.COUNTING).line(oilLine(12, 12)).line(filterLotALine(4, null))
                .line(filterLotBLine(3, 3)).task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
        List<Long> ids = lineIds(count);

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(ids.get(1)), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.lines.length()").value(2));

        assertThat(lineIds(count)).containsExactly(ids.get(0), ids.get(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_count_line WHERE stock_count_id = ? "
                + "AND counted_qty IS NULL", Integer.class, count)).isZero();
    }

    @Test
    void removingACountedLineIsRefused() throws Exception {
        long count = submittedWithoutDifference();

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(lineIds(count).getFirst()), List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG236"));

        assertStillCounting(count);
        assertThat(lineIds(count)).hasSize(2);
    }

    @Test
    void removingALineOfAnotherCountIsRefused() throws Exception {
        long count = submittedWithoutDifference();
        long other = count(StockCountStatus.COUNTING).line(oilLine(12, null)).insert();

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(lineIds(other).getFirst()), List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG236"));

        assertStillCounting(count);
        assertThat(lineIds(other)).hasSize(1);
    }

    @Test
    void keepingAnUncountedLineIsRefused() throws Exception {
        long count = count(StockCountStatus.COUNTING).line(oilLine(12, 12)).line(filterLotALine(4, null))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG236"));

        assertStillCounting(count);
    }

    @Test
    void aDifferenceWithoutAReasonIsRefused() throws Exception {
        long count = submittedWithDifference();
        long oilLine = lineIds(count).getFirst();

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG236"));
        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(), List.of(reason(oilLine, "   "))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG236"));

        assertStillCounting(count);
        assertThat(notificationsOf(owner)).isEmpty();
    }

    @Test
    void aLineRemovedAfterTheCountWentToTheOwnerIsRefused() throws Exception {
        long count = count(StockCountStatus.COUNTING).confirmedOnce().line(oilLine(12, 12))
                .line(filterLotALine(4, null)).task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(lineIds(count).get(1)), List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG243"));

        assertStillCounting(count);
        assertThat(lineIds(count)).hasSize(2);
    }

    @Test
    void theCounterCannotConfirmTheirOwnResultButTheOtherManagerCan() throws Exception {
        long count = count(StockCountStatus.COUNTING).line(oilLine(12, 12))
                .task(manager, WarehouseTaskStatus.RESULT_SUBMITTED).insert();

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG124"));
        assertStillCounting(count);
        assertThat(countStatus(count)).isEqualTo("COUNTING");

        postAs(manager2Token, url(count, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(taskStatuses(count)).containsExactly("DONE");
    }

    @Test
    void onlyTheManagerOfTheWarehouseConfirms() throws Exception {
        long count = submittedWithoutDifference();

        postAs(otherManagerToken, url(count, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        postAs(ownerToken, url(count, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        postAs(staffToken, url(count, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        postAs(managerToken, url(count + 1000, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG245"));

        assertStillCounting(count);
    }

    @Test
    void aCountThatIsNotWaitingForConfirmationIsRefused() throws Exception {
        long notSubmitted = count(StockCountStatus.COUNTING).line(oilLine(12, 12))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();
        long awaiting = count(StockCountStatus.AWAITING_APPROVAL).line(oilLine(12, 12))
                .task(staff, WarehouseTaskStatus.DONE).insert();
        long restarting = count(StockCountStatus.COUNTING).awaitingRestart().line(oilLine(12, 12))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();

        for (long count : List.of(notSubmitted, awaiting, restarting)) {
            postAs(managerToken, url(count, "confirm"), confirmBody(List.of(), List.of()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG235"));
        }
        assertThat(countStatus(notSubmitted)).isEqualTo("COUNTING");
        assertThat(countStatus(awaiting)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void aClosedPeriodBlocksTheConfirmation() throws Exception {
        long count = submittedWithoutDifference();
        closePeriod(period);

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(), List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG232"));

        assertStillCounting(count);
    }

    // ---------------------------------------------------------------------------------------------- recount

    @Test
    void theOwnerReturnsOneLineAndTheCounterAndManagersAreTold() throws Exception {
        long count = count(StockCountStatus.AWAITING_APPROVAL).line(oilLine(12, 10).because("Rơi vỡ"))
                .line(filterLotALine(4, 3).because("Mất")).task(staff, WarehouseTaskStatus.DONE).insert();
        long oilLine = lineIds(count).getFirst();
        long filterLine = lineIds(count).get(1);

        postAs(ownerToken, url(count, "recount"), Map.of("lineIds", List.of(oilLine), "reason", "Đếm lại kệ A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COUNTING"))
                .andExpect(jsonPath("$.awaitingBookQuantities").value(true));

        assertThat(countStatus(count)).isEqualTo("COUNTING");
        assertThat(jdbc.queryForObject("SELECT counting_started_at IS NULL AND confirmed_at IS NOT NULL "
                + "FROM stock_count WHERE id = ?", Boolean.class, count)).isTrue();
        assertThat(jdbc.queryForList("SELECT id, recount_requested, recount_reason FROM stock_count_line "
                + "WHERE stock_count_id = ? ORDER BY id", count))
                .extracting(r -> r.get("recount_requested"), r -> r.get("recount_reason"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple(true, "Đếm lại kệ A"),
                        org.assertj.core.groups.Tuple.tuple(false, null));
        assertThat(filterLine).isNotEqualTo(oilLine);
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT assigned_to FROM warehouse_task WHERE stock_count_id = ?", Long.class,
                count)).isEqualTo(staff.getId());
        assertThat(notificationsOf(staff)).containsExactly(List.of("STOCK_COUNT_RECOUNT", "STOCK_COUNT", count));
        assertThat(notificationsOf(manager)).containsExactly(List.of("STOCK_COUNT_RECOUNT", "STOCK_COUNT", count));
        assertThat(notificationsOf(manager2)).hasSize(1);
        assertThat(movementsOf(count)).isEmpty();
    }

    @Test
    void afterAnOwnerRecountNoLineCanBeRemoved() throws Exception {
        long count = count(StockCountStatus.AWAITING_APPROVAL).line(oilLine(12, 12)).line(filterLotALine(4, 4))
                .task(staff, WarehouseTaskStatus.DONE).insert();
        long filterLine = lineIds(count).get(1);
        postAs(ownerToken, url(count, "recount"), Map.of("lineIds", List.of(filterLine), "reason", "Kiểm tra lại"))
                .andExpect(status().isOk());

        // The counter counts the line again and sends the result (UC-93 is not part of this test): seed that state.
        jdbc.update("UPDATE stock_count SET counting_started_at = ? WHERE id = ?", java.time.LocalDateTime.now(clock),
                count);
        jdbc.update("UPDATE stock_count_line SET counted_qty = NULL, counted_by = NULL, counted_at = NULL "
                + "WHERE id = ?", filterLine);
        jdbc.update("UPDATE warehouse_task SET status = 'RESULT_SUBMITTED', result_submitted_at = ? "
                + "WHERE stock_count_id = ?", java.time.LocalDateTime.now(clock), count);

        postAs(managerToken, url(count, "confirm"), confirmBody(List.of(filterLine), List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG243"));
        assertThat(lineIds(count)).hasSize(2);
    }

    @Test
    void theManagerReturnsLinesOfASubmittedResult() throws Exception {
        long count = submittedWithoutDifference();
        List<Long> ids = lineIds(count);

        postAs(managerToken, url(count, "recount"), Map.of("lineIds", ids, "reason", "Nghi sai số"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COUNTING"));

        assertThat(countStatus(count)).isEqualTo("COUNTING");
        assertThat(taskStatuses(count)).containsExactly("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_count_line WHERE stock_count_id = ? "
                + "AND recount_requested", Integer.class, count)).isEqualTo(2);
        assertThat(notificationsOf(staff)).containsExactly(List.of("STOCK_COUNT_RECOUNT", "STOCK_COUNT", count));
        assertThat(notificationsOf(manager2)).isEmpty();
    }

    @Test
    void whoMayReturnLinesDependsOnTheStatus() throws Exception {
        long counting = submittedWithoutDifference();
        long notSubmitted = count(StockCountStatus.COUNTING).line(oilLine(12, 12))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();
        long awaiting = count(StockCountStatus.AWAITING_APPROVAL).line(oilLine(12, 10).because("x"))
                .task(staff, WarehouseTaskStatus.DONE).insert();

        Map<String, Object> body = Map.of("lineIds", List.of(lineIds(counting).getFirst()), "reason", "Đếm lại");
        postAs(otherManagerToken, url(counting, "recount"), body)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        postAs(staffToken, url(counting, "recount"), body)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        // Owner only returns what waits for approval, the manager only what the counter submitted.
        postAs(ownerToken, url(counting, "recount"), body)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG235"));
        postAs(managerToken, url(notSubmitted, "recount"),
                Map.of("lineIds", List.of(lineIds(notSubmitted).getFirst()), "reason", "Đếm lại"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG235"));
        postAs(managerToken, url(awaiting, "recount"),
                Map.of("lineIds", List.of(lineIds(awaiting).getFirst()), "reason", "Đếm lại"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG235"));

        assertStillCounting(counting);
        assertThat(countStatus(awaiting)).isEqualTo("AWAITING_APPROVAL");
        assertThat(notificationsOf(staff)).isEmpty();
    }

    @Test
    void aLineOfAnotherCountCannotBeReturned() throws Exception {
        long count = submittedWithoutDifference();
        long other = count(StockCountStatus.COUNTING).line(oilLine(12, 12)).insert();

        postAs(managerToken, url(count, "recount"),
                Map.of("lineIds", List.of(lineIds(other).getFirst()), "reason", "Đếm lại"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG237"));

        assertStillCounting(count);
    }
}
