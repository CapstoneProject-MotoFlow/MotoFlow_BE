package fu.hl.g64.motorflow.stockcount;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fu.hl.g64.motorflow.support.Report51Support.NO_TOKEN;
import static fu.hl.g64.motorflow.support.Report51Support.as;
import static fu.hl.g64.motorflow.support.Report51Support.atTheSameTime;
import static fu.hl.g64.motorflow.support.Report51Support.expectError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Report 5.1, function ApproveStockCount: StockCountService.approve, POST /api/v1/stock-counts/{id}/approve (UC-95). */
@Tag("report51")
class ApproveStockCountUnitTest extends StockCountTestData {

    @Test
    @DisplayName("UTC018 Normal: lot A book 4 counted 3 is adjusted to 3 with one movement")
    void utc018() throws Exception {
        long count = lotAShortBy(1);

        approve(ownerToken, count, "Approved").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(availableOfLot(filterLotA)).isEqualTo(3);
        assertThat(availableOf(store, filter)).isEqualTo(8);
        List<Map<String, Object>> movements = movementsOf(count);
        assertThat(movements).hasSize(1);
        assertThat(movements.getFirst()).containsEntry("lot_id", filterLotA).containsEntry("quantity_change", -1)
                .containsEntry("movement_type", "COUNT_ADJUSTMENT");
        assertThat(countStatus(count)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT approved_by FROM stock_count WHERE id = ?", Long.class, count))
                .isEqualTo(owner.getId());
        assertThat(jdbc.queryForObject("SELECT decision_reason FROM stock_count WHERE id = ?", String.class, count))
                .isEqualTo("Approved");
    }

    @Test
    @DisplayName("UTC019 Normal: a part without a lot counted 2 against book 0 creates a surplus lot of 2")
    void utc019() throws Exception {
        long count = awaiting(SeedLine.of(pad, null, null, 0, 2).because("Found"));

        approve(ownerToken, count, null).andExpect(status().isOk());

        Map<String, Object> lot = jdbc.queryForMap("SELECT * FROM lot WHERE stock_count_id = ? "
                + "AND source_type = 'COUNT_SURPLUS'", count);
        assertThat(lot).containsEntry("store_id", store).containsEntry("product_id", pad);
        assertThat(availableOfLot((Long) lot.get("id"))).isEqualTo(2);
        assertThat(availableOf(store, pad)).isEqualTo(2);
        List<Map<String, Object>> movements = movementsOf(count);
        assertThat(movements).hasSize(1);
        assertThat(movements.getFirst()).containsEntry("lot_id", lot.get("id")).containsEntry("quantity_change", 2)
                .containsEntry("movement_type", "COUNT_ADJUSTMENT");
        assertThat(countStatus(count)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("UTC020 Boundary: lot A book 4 counted 0 and holding 4 goes down to 0")
    void utc020() throws Exception {
        long count = lotAShortBy(4);

        approve(ownerToken, count, null).andExpect(status().isOk());

        assertThat(availableOfLot(filterLotA)).isZero();
        assertThat(movementsOf(count)).hasSize(1);
        assertThat(countStatus(count)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("UTC021 Boundary: lot A book 4 counted 0 but lot A now holds only 3")
    void utc021() throws Exception {
        long count = lotAShortBy(4);
        sellFromLot(filterLotA, 1);

        approve(ownerToken, count, null).andExpect(expectError(HttpStatus.CONFLICT, "MSG241"));

        assertNothingPosted(count);
        assertThat(availableOfLot(filterLotA)).isEqualTo(3);
    }

    @Test
    @DisplayName("UTC022 Abnormal: Branch Manager of the store may not approve")
    void utc022() throws Exception {
        long count = lotAShortBy(1);

        approve(managerToken, count, null).andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNothingPosted(count);
        assertThat(availableOfLot(filterLotA)).isEqualTo(4);
    }

    @Test
    @DisplayName("UTC023 Abnormal: the count is still COUNTING")
    void utc023() throws Exception {
        long count = count(StockCountStatus.COUNTING).line(filterLotALine(4, 3))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();

        approve(ownerToken, count, null).andExpect(expectError(HttpStatus.CONFLICT, "MSG235"));

        assertThat(countStatus(count)).isEqualTo("COUNTING");
        assertThat(movementsOf(count)).isEmpty();
        assertThat(availableOfLot(filterLotA)).isEqualTo(4);
    }

    @Test
    @DisplayName("UTC024 Abnormal: the inventory period of the count is closed")
    void utc024() throws Exception {
        long count = lotAShortBy(1);
        closePeriod(period);

        approve(ownerToken, count, null).andExpect(expectError(HttpStatus.CONFLICT, "MSG232"));

        assertNothingPosted(count);
        assertThat(availableOfLot(filterLotA)).isEqualTo(4);
    }

    @Test
    @DisplayName("UTC025 Abnormal: not signed in")
    void utc025() throws Exception {
        long count = lotAShortBy(1);

        approve(NO_TOKEN, count, "Approved").andExpect(expectError(HttpStatus.UNAUTHORIZED, "MSG06"));

        assertNothingPosted(count);
    }

    @Test
    @DisplayName("UTC026 Abnormal: Staff of the store may not approve")
    void utc026() throws Exception {
        long count = lotAShortBy(1);

        approve(staffToken, count, "Approved").andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNothingPosted(count);
    }

    @Test
    @DisplayName("UTC027 Abnormal: concurrency, Owner clicks Approve twice at the same time")
    void utc027() throws Exception {
        long count = lotAShortBy(1);

        List<MvcResult> results = atTheSameTime(() -> approve(ownerToken, count, "Approved").andReturn(),
                () -> approve(ownerToken, count, "Approved").andReturn());

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 409);
        MvcResult loser = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst()
                .orElseThrow();
        assertThat(JsonPath.<String>read(loser.getResponse().getContentAsString(), "$.code")).isEqualTo("MSG235");
        assertThat(availableOfLot(filterLotA)).isEqualTo(3);
        assertThat(availableOf(store, filter)).isEqualTo(8);
        assertThat(movementsOf(count)).hasSize(1);
        assertThat(countStatus(count)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("UTC028 Abnormal: concurrency, a sale of 2 from lot A lands while Owner approves a difference of -1")
    void utc028() throws Exception {
        long count = lotAShortBy(1);

        // The sale takes 2 from lot A and from the part's balance in its own transaction, which stays open
        // until the approval is blocked behind the part's balance row it needs.
        MvcResult approval = whileAnotherTransactionHoldsTheRow("WITH sold AS (UPDATE lot_balance SET quantity = quantity - 2 "
                        + "WHERE lot_id = ? AND stock_status = 'AVAILABLE' RETURNING 1) "
                        + "UPDATE stock_balance SET available_qty = available_qty - 2 "
                        + "WHERE store_id = ? AND product_id = ?",
                () -> approve(ownerToken, count, "Approved").andReturn(), filterLotA, store, filter);

        assertThat(approval.getResponse().getStatus()).isEqualTo(200);
        assertThat(availableOfLot(filterLotA)).isEqualTo(1);
        assertThat(availableOf(store, filter)).isEqualTo(6);
        assertThat(movementsOf(count)).hasSize(1);
        assertThat(countStatus(count)).isEqualTo("COMPLETED");
    }

    /** AWAITING_APPROVAL: lot A book 4, counted {@code 4 - shortBy}. */
    private long lotAShortBy(int shortBy) {
        return awaiting(filterLotALine(4, 4 - shortBy).because("Broken"));
    }

    private long awaiting(SeedLine line) {
        return count(StockCountStatus.AWAITING_APPROVAL).line(line).task(staff, WarehouseTaskStatus.DONE).insert();
    }

    private ResultActions approve(String token, long count, String decisionReason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("decisionReason", decisionReason);
        return mvc.perform(as(token, post("/api/v1/stock-counts/" + count + "/approve"), json(body)));
    }

    private void assertNothingPosted(long count) {
        assertThat(movementsOf(count)).isEmpty();
        assertThat(countStatus(count)).isEqualTo("AWAITING_APPROVAL");
    }
}
