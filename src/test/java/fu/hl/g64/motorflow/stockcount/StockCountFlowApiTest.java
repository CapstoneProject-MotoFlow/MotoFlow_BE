package fu.hl.g64.motorflow.stockcount;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-105 and UC-90 to UC-95 end to end, through the real API only: the Owner requests a count, the Branch Manager
 * answers it and gives it to Staff, Staff counts, the Branch Manager confirms, the Owner sends one line back, Staff
 * counts it again, the Branch Manager confirms again and the Owner approves.
 */
class StockCountFlowApiTest extends StockCountTestData {

    private static final String COUNTS = "/api/v1/stock-counts";

    @Test
    void aRequestedCountGoesFromRequestToApprovedStock() throws Exception {
        long request = idOf(postAs(ownerToken, "/api/v1/stock-count-requests", Map.of("storeId", store,
                "scopeType", "PRODUCTS", "reason", "Kiểm định kỳ", "productIds", List.of(oil, filter)))
                .andExpect(status().is2xxSuccessful()));

        getAs(managerToken, COUNTS + "/prefill?requestId=" + request).andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(2));
        String created = body(postAs(managerToken, COUNTS, Map.of("countType", "CYCLE", "countDate", TODAY.toString(),
                "scopeType", "PRODUCTS", "productIds", List.of(oil, filter), "stockCountRequestId", request))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lines.length()").value(4)));
        long count = ((Number) JsonPath.read(created, "$.id")).longValue();
        assertThat(requestStatus(request)).isEqualTo("DONE");
        long oilLine = lineOf(created, oil, null);
        long lotALine = lineOf(created, filter, filterLotA);
        long lotBLine = lineOf(created, filter, filterLotB);
        long plainLine = lineOf(created, filter, null);

        postAs(managerToken, "/api/v1/warehouse-tasks", Map.of("taskType", "COUNTING", "documentId", count,
                "assigneeId", staff.getId())).andExpect(status().isOk());

        // Staff counts without seeing the book quantities.
        postAs(staffToken, COUNTS + "/" + count + "/start", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COUNTING"))
                .andExpect(jsonPath("$.lines[0].bookQty").doesNotExist());
        putAs(staffToken, COUNTS + "/" + count + "/lines", Map.of("lines", List.of(line(oilLine, 11))))
                .andExpect(status().isOk());
        postAs(staffToken, COUNTS + "/" + count + "/submit", Map.of("lines",
                List.of(line(lotALine, 4), line(lotBLine, 2), line(plainLine, 2)))).andExpect(status().isOk());

        postAs(managerToken, COUNTS + "/" + count + "/confirm", Map.of("reasons",
                List.of(reason(oilLine, "Đổ vỡ"), reason(lotBLine, "Chưa rõ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));

        // The Owner doubts lot B; Staff counts that line again against a fresh book quantity (BR-79).
        postAs(ownerToken, COUNTS + "/" + count + "/recount", Map.of("lineIds", List.of(lotBLine),
                "reason", "Đếm lại lô B")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COUNTING"));
        postAs(staffToken, COUNTS + "/" + count + "/start", null).andExpect(status().isOk());
        postAs(staffToken, COUNTS + "/" + count + "/submit", Map.of("lines", List.of(line(lotBLine, 3))))
                .andExpect(status().isOk());
        getAs(managerToken, COUNTS + "/" + count).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.id == " + lotBLine + ")].previousCountedQty").value(2))
                .andExpect(jsonPath("$.lines[?(@.id == " + lotBLine + ")].countedQty").value(3));
        postAs(managerToken, COUNTS + "/" + count + "/confirm", Map.of("reasons", List.of(reason(oilLine, "Đổ vỡ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));

        postAs(ownerToken, COUNTS + "/" + count + "/approve", Map.of("decisionReason", "Đồng ý"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Only the oil shortage is posted: one off the oldest lot without expiry, at the end of the count day.
        assertThat(availableOfLot(oilLot1)).isEqualTo(4);
        assertThat(availableOfLot(oilLot2)).isEqualTo(7);
        assertThat(availableOfLot(filterLotB)).isEqualTo(3);
        assertThat(availableOf(store, oil)).isEqualTo(11);
        List<Map<String, Object>> movements = movementsOf(count);
        assertThat(movements).hasSize(1);
        assertThat(movements.getFirst()).containsEntry("lot_id", oilLot1).containsEntry("quantity_change", -1)
                .containsEntry("movement_type", "COUNT_ADJUSTMENT").containsEntry("inventory_period_id", period);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM stock_movement WHERE stock_count_id = ?",
                Timestamp.class, count).toLocalDateTime()).isEqualTo(TODAY.atTime(23, 59, 59));
        assertThat(taskStatuses(count)).containsOnly("DONE");
        assertThat(notificationsOf(manager)).contains(List.of("STOCK_COUNT_APPROVED", "STOCK_COUNT", count));
        getAs(staffToken, COUNTS).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.items[0].id").value(count))
                .andExpect(jsonPath("$.counts.items[0].status").value("COMPLETED"));
    }

    private static Map<String, Object> line(long lineId, int counted) {
        return Map.of("lineId", lineId, "countedQty", counted);
    }

    private static Map<String, Object> reason(long lineId, String why) {
        return Map.of("lineId", lineId, "reason", why);
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private static long idOf(ResultActions actions) throws Exception {
        return ((Number) JsonPath.read(body(actions), "$.id")).longValue();
    }

    /** The id of the line of {@code product} on {@code lot}; null is the line of the lots without expiry. */
    private static long lineOf(String detail, long product, Long lot) {
        List<Number> ids = JsonPath.read(detail, "$.lines[?(@.productId == " + product + " && @.lotId == "
                + (lot == null ? "null" : lot) + ")].id");
        assertThat(ids).hasSize(1);
        return ids.getFirst().longValue();
    }
}
