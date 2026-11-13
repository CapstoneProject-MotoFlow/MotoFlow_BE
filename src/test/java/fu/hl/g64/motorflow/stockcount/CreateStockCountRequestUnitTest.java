package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static fu.hl.g64.motorflow.support.Report51Support.as;
import static fu.hl.g64.motorflow.support.Report51Support.expectError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-105: StockCountRequestService.create, UTC029 to UTC032. Store #2 is {@link #store}, with storage area "Kệ A". */
@Tag("report51")
class CreateStockCountRequestUnitTest extends StockCountTestData {

    private static final String URL = "/api/v1/stock-count-requests";

    private String body(ScopeType scope, String scopeNote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeId", store);
        body.put("scopeType", scope);
        body.put("scopeNote", scopeNote);
        body.put("reason", "Kiem cuoi quy");
        body.put("productIds", null);
        return json(body);
    }

    private int requestCount() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_count_request", Integer.class);
    }

    @Test
    @DisplayName("UTC029 Normal: the Owner requests a count of the whole warehouse")
    void utc029() throws Exception {
        mvc.perform(as(ownerToken, post(URL), body(ScopeType.WHOLE, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        long id = id("SELECT id FROM stock_count_request");
        assertThat(jdbc.queryForMap("SELECT store_id, scope_type, status, reason, requested_by "
                + "FROM stock_count_request WHERE id = ?", id))
                .containsEntry("store_id", store).containsEntry("scope_type", "WHOLE")
                .containsEntry("status", "PENDING").containsEntry("reason", "Kiem cuoi quy")
                .containsEntry("requested_by", owner.getId());
        assertThat(notificationsOf(manager)).containsExactly(List.of("STOCK_COUNT_REQUESTED", "STOCK_COUNT_REQUEST", id));
        assertThat(notificationsOf(otherManager)).isEmpty();
    }

    @Test
    @DisplayName("UTC030 Abnormal: an area that is not in the store is refused")
    void utc030() throws Exception {
        mvc.perform(as(ownerToken, post(URL), body(ScopeType.AREA, "Ke Z")))
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG02"));

        assertThat(requestCount()).isZero();
        assertThat(notificationsOf(manager)).isEmpty();
    }

    @Test
    @DisplayName("UTC031 Abnormal: the period of this month is closed")
    void utc031() throws Exception {
        closePeriod(period);

        mvc.perform(as(ownerToken, post(URL), body(ScopeType.WHOLE, null)))
                .andExpect(expectError(HttpStatus.CONFLICT, "MSG232"));

        assertThat(requestCount()).isZero();
        assertThat(notificationsOf(manager)).isEmpty();
    }

    @Test
    @DisplayName("UTC032 Abnormal: the Branch Manager of the store cannot request a count")
    void utc032() throws Exception {
        mvc.perform(as(managerToken, post(URL), body(ScopeType.WHOLE, null)))
                .andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertThat(requestCount()).isZero();
        assertThat(notificationsOf(manager)).isEmpty();
    }
}
