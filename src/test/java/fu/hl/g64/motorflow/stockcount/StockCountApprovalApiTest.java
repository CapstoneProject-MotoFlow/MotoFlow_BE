package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-95 Approve Stock Count: the differences are posted lot by lot into the count's period (BR-81, BR-83). */
class StockCountApprovalApiTest extends StockCountTestData {

    private String url(long count) {
        return "/api/v1/stock-counts/" + count + "/approve";
    }

    private SeededCount awaiting() {
        return new SeededCount();
    }

    /** An AWAITING_APPROVAL count whose counting task is done, as UC-94 leaves it. */
    private final class SeededCount {
        private final StockCountTestData.CountSeed seed = count(StockCountStatus.AWAITING_APPROVAL);

        SeededCount line(StockCountTestData.SeedLine line) {
            seed.line(line);
            return this;
        }

        long insert() {
            return seed.task(staff, WarehouseTaskStatus.DONE).insert();
        }
    }

    private BigDecimal movingAverage(long product) {
        return jdbc.queryForObject("SELECT moving_avg_cost FROM product WHERE id = ?", BigDecimal.class, product);
    }

    private void assertNothingPosted(long count) {
        assertThat(movementsOf(count)).isEmpty();
        assertThat(countStatus(count)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void aShortageOnALotWithExpiryTakesExactlyThatLot() throws Exception {
        long count = awaiting().line(filterLotALine(4, 3).because("Hỏng")).line(filterLotBLine(3, 3)).insert();

        postAs(ownerToken, url(count), Map.of("decisionReason", "  Đồng ý  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(availableOfLot(filterLotA)).isEqualTo(3);
        assertThat(availableOfLot(filterLotB)).isEqualTo(3);
        assertThat(availableOfLot(filterLotPlain)).isEqualTo(2);
        assertThat(availableOf(store, filter)).isEqualTo(8);
        List<Map<String, Object>> movements = movementsOf(count);
        assertThat(movements).hasSize(1);
        assertThat(movements.getFirst()).containsEntry("lot_id", filterLotA).containsEntry("quantity_change", -1)
                .containsEntry("movement_type", "COUNT_ADJUSTMENT").containsEntry("inventory_period_id", period);
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM stock_movement WHERE stock_count_id = ?",
                BigDecimal.class, count)).isEqualByComparingTo("50000");

        assertThat(countStatus(count)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT approved_by FROM stock_count WHERE id = ?", Long.class, count))
                .isEqualTo(owner.getId());
        assertThat(jdbc.queryForObject("SELECT approved_at IS NOT NULL FROM stock_count WHERE id = ?", Boolean.class,
                count)).isTrue();
        assertThat(jdbc.queryForObject("SELECT decision_reason FROM stock_count WHERE id = ?", String.class, count))
                .isEqualTo("Đồng ý");
        assertThat(taskStatuses(count)).endsWith("DONE");
        assertThat(notificationsOf(manager)).contains(List.of("STOCK_COUNT_APPROVED", "STOCK_COUNT", count));
    }

    @Test
    void aShortageWithNoLotTakesTheOldestLotFirstAndSpillsToTheNext() throws Exception {
        // Oil has two lots without expiry: 5 received 01/10 and 7 received 05/10. Counted 4 of 12.
        long count = awaiting().line(oilLine(12, 4).because("Rò rỉ")).insert();

        postAs(ownerToken, url(count), Map.of()).andExpect(status().isOk());

        assertThat(availableOfLot(oilLot1)).isZero();
        assertThat(availableOfLot(oilLot2)).isEqualTo(4);
        assertThat(availableOf(store, oil)).isEqualTo(4);
        List<Map<String, Object>> movements = movementsOf(count);
        assertThat(movements).hasSize(2);
        assertThat(movements.get(0)).containsEntry("lot_id", oilLot1).containsEntry("quantity_change", -5);
        assertThat(movements.get(1)).containsEntry("lot_id", oilLot2).containsEntry("quantity_change", -3);
        assertThat(jdbc.queryForObject("SELECT decision_reason FROM stock_count WHERE id = ?", String.class, count))
                .isNull();
    }

    @Test
    void aShortageWithNoLotNeverTouchesLotsThatHaveAnExpiry() throws Exception {
        // Filter's lot without expiry holds 2; its lots A and B (expiry) are not part of the line.
        long count = awaiting().line(filterPlainLine(2, 0).because("Mất")).insert();

        postAs(ownerToken, url(count), Map.of()).andExpect(status().isOk());

        assertThat(availableOfLot(filterLotPlain)).isZero();
        assertThat(availableOfLot(filterLotA)).isEqualTo(4);
        assertThat(availableOfLot(filterLotB)).isEqualTo(3);
        assertThat(movementsOf(count)).hasSize(1);
    }

    @Test
    void aSurplusWithNoLotCreatesACountSurplusLotAtTheMovingAverage() throws Exception {
        long count = awaiting().line(oilLine(12, 15).because("Nhập thiếu sổ")).insert();

        postAs(ownerToken, url(count), Map.of()).andExpect(status().isOk());

        Map<String, Object> lot = jdbc.queryForMap("SELECT id, source_type, stock_count_id, store_id, received_date, "
                + "purchase_unit_price, expiry_date, goods_receipt_note_line_id FROM lot WHERE stock_count_id = ? "
                + "AND source_type = 'COUNT_SURPLUS'", count);
        assertThat(lot).containsEntry("store_id", store).containsEntry("purchase_unit_price", 85_000L)
                .containsEntry("received_date", java.sql.Date.valueOf(TODAY));
        assertThat(lot.get("expiry_date")).isNull();
        assertThat(lot.get("goods_receipt_note_line_id")).isNull();
        long surplus = (Long) lot.get("id");
        assertThat(availableOfLot(surplus)).isEqualTo(3);
        assertThat(availableOfLot(oilLot1)).isEqualTo(5);
        assertThat(availableOfLot(oilLot2)).isEqualTo(7);
        assertThat(availableOf(store, oil)).isEqualTo(15);
        List<Map<String, Object>> movements = movementsOf(count);
        assertThat(movements).hasSize(1);
        assertThat(movements.getFirst()).containsEntry("lot_id", surplus).containsEntry("quantity_change", 3)
                .containsEntry("movement_type", "COUNT_ADJUSTMENT").containsEntry("inventory_period_id", period);
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM stock_movement WHERE stock_count_id = ?",
                BigDecimal.class, count)).isEqualByComparingTo("85000");
        // The surplus came in at the average, so the average does not move (BR-74).
        assertThat(movingAverage(oil)).isEqualByComparingTo("85000");
    }

    @Test
    void aSurplusOnALotWithExpiryAddsToThatLot() throws Exception {
        long count = awaiting().line(filterLotBLine(3, 5).because("Đếm nhầm kho")).insert();

        postAs(ownerToken, url(count), Map.of()).andExpect(status().isOk());

        assertThat(availableOfLot(filterLotB)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lot", Integer.class)).isEqualTo(5);
        assertThat(movementsOf(count)).hasSize(1);
    }

    @Test
    void aSaleAfterTheBookQuantityIsKeptOnlyTheDifferenceIsPosted() throws Exception {
        // Book 12, counted 10 (short by 2). Meanwhile 2 oil were sold from the newer lot: the book is 10 by now.
        long count = awaiting().line(oilLine(12, 10).because("Hao hụt")).insert();
        sellFromLot(oilLot2, 2);

        postAs(ownerToken, url(count), Map.of()).andExpect(status().isOk());

        // Current 10 plus the difference of -2 (BR-79), taken from the oldest lot.
        assertThat(availableOf(store, oil)).isEqualTo(8);
        assertThat(availableOfLot(oilLot1)).isEqualTo(3);
        assertThat(availableOfLot(oilLot2)).isEqualTo(5);
    }

    @Test
    void aLotThatWouldGoNegativeStopsTheWholeApprovalBeforeAnyMovement() throws Exception {
        // Oil is fine on its own (-1), but the lot A line is short by 4 and 3 of lot A's 4 were sold meanwhile.
        long count = awaiting().line(filterLotALine(4, 0).because("Mất")).line(oilLine(12, 11).because("Hao"))
                .insert();
        sellFromLot(filterLotA, 3);

        postAs(ownerToken, url(count), Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG241"));

        assertNothingPosted(count);
        assertThat(availableOfLot(filterLotA)).isEqualTo(1);
        assertThat(availableOfLot(oilLot1)).isEqualTo(5);
        assertThat(availableOfLot(oilLot2)).isEqualTo(7);
        assertThat(availableOf(store, oil)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT approved_at IS NULL FROM stock_count WHERE id = ?", Boolean.class,
                count)).isTrue();
    }

    @Test
    void aShortageWithNoLotBeyondTheLotsWithoutExpiryIsRefusedToo() throws Exception {
        // 7 oil were sold since the book quantity was taken, leaving 5; the line is short by 8.
        long count = awaiting().line(oilLine(12, 4).because("Mất")).insert();
        sellFromLot(oilLot2, 7);

        postAs(ownerToken, url(count), Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG241"));

        assertNothingPosted(count);
        assertThat(availableOf(store, oil)).isEqualTo(5);
    }

    @Test
    void aCountOfLastMonthPostsIntoThatMonthAtTheEndOfTheCountDay() throws Exception {
        long september = period(LocalDate.of(2026, 9, 30), true);
        long count = count(StockCountStatus.AWAITING_APPROVAL).on(LocalDate.of(2026, 9, 30), september)
                .line(filterLotALine(4, 3).because("Hỏng")).line(oilLine(12, 14).because("Thừa"))
                .task(staff, WarehouseTaskStatus.DONE).insert();
        today(LocalDate.of(2026, 10, 2));

        postAs(ownerToken, url(count), Map.of()).andExpect(status().isOk());

        List<Map<String, Object>> movements = movementsOf(count);
        assertThat(movements).hasSize(2);
        for (Map<String, Object> movement : movements) {
            assertThat(movement).containsEntry("inventory_period_id", september);
            assertThat(((Timestamp) movement.get("occurred_at")).toLocalDateTime())
                    .isEqualTo(LocalDateTime.of(2026, 9, 30, 23, 59, 59));
        }
        // The October period gets nothing from this count.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE inventory_period_id = ?",
                Integer.class, period)).isZero();
        assertThat(countStatus(count)).isEqualTo("COMPLETED");
    }

    @Test
    void aClosedPeriodRefusesTheApproval() throws Exception {
        long count = awaiting().line(filterLotALine(4, 3).because("Hỏng")).insert();
        closePeriod(period);

        postAs(ownerToken, url(count), Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG232"));

        assertNothingPosted(count);
    }

    @Test
    void onlyTheOwnerApproves() throws Exception {
        long count = awaiting().line(filterLotALine(4, 3).because("Hỏng")).insert();

        postAs(managerToken, url(count), Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        postAs(staffToken, url(count), Map.of()).andExpect(status().isForbidden());

        assertNothingPosted(count);
    }

    @Test
    void approvingTwiceIsRefusedAndPostsOnce() throws Exception {
        long count = awaiting().line(filterLotALine(4, 3).because("Hỏng")).insert();

        postAs(ownerToken, url(count), Map.of()).andExpect(status().isOk());
        postAs(ownerToken, url(count), Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG235"));

        assertThat(movementsOf(count)).hasSize(1);
        assertThat(availableOfLot(filterLotA)).isEqualTo(3);
    }

    @Test
    void aCountThatIsNotAwaitingApprovalIsRefused() throws Exception {
        long count = count(StockCountStatus.COUNTING).line(filterLotALine(4, 3).because("Hỏng"))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();

        postAs(ownerToken, url(count), Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG235"));

        assertThat(movementsOf(count)).isEmpty();
    }

    @Test
    void aCountThatDoesNotExistIsNotFound() throws Exception {
        postAs(ownerToken, url(999_999), Map.of())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG245"));
    }
}
