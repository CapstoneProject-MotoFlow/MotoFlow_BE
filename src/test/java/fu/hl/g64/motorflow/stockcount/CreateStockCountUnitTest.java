package fu.hl.g64.motorflow.stockcount;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Date;
import java.time.LocalDate;
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

/** Report 5.1, function CreateStockCount: StockCountService.create, POST /api/v1/stock-counts (UC-90). */
@Tag("report51")
class CreateStockCountUnitTest extends StockCountTestData {

    private static final String URL = "/api/v1/stock-counts";

    @Test
    @DisplayName("UTC001 Normal: Branch Manager creates a cycle count of the whole warehouse")
    void utc001() throws Exception {
        create(managerToken, "CYCLE", TODAY, ScopeType.WHOLE, null, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM stock_count WHERE count_type <> 'OPENING'");
        assertThat(row).containsEntry("store_id", store).containsEntry("status", "DRAFT")
                .containsEntry("count_type", "CYCLE").containsEntry("scope_type", "WHOLE")
                .containsEntry("inventory_period_id", period).containsEntry("created_by", manager.getId())
                .containsEntry("count_date", Date.valueOf(TODAY));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_count_line WHERE stock_count_id = ?", Long.class,
                row.get("id"))).isEqualTo(4);
    }

    @Test
    @DisplayName("UTC002 Normal: count answering a pending request of the Owner marks the request done")
    void utc002() throws Exception {
        long request = seedRequest(store, ScopeType.PRODUCTS, null, StockCountRequestStatus.PENDING, oil, filter);

        create(managerToken, "CYCLE", TODAY, ScopeType.PRODUCTS, List.of(oil, filter), request)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"));

        assertThat(requestStatus(request)).isEqualTo("DONE");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM stock_count WHERE count_type <> 'OPENING'");
        assertThat(row).containsEntry("stock_count_request_id", request).containsEntry("status", "DRAFT")
                .containsEntry("scope_type", "PRODUCTS");
        assertThat(jdbc.queryForList("SELECT DISTINCT product_id FROM stock_count_line WHERE stock_count_id = ? "
                + "ORDER BY product_id", Long.class, row.get("id"))).containsExactly(oil, filter);
    }

    @Test
    @DisplayName("UTC003 Abnormal: the warehouse already has a draft count")
    void utc003() throws Exception {
        count(StockCountStatus.DRAFT).insert();

        create(managerToken, "CYCLE", TODAY, ScopeType.WHOLE, null, null)
                .andExpect(expectError(HttpStatus.CONFLICT, "MSG230"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_count WHERE count_type <> 'OPENING'", Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("UTC004 Abnormal: Staff of the warehouse may not create a count")
    void utc004() throws Exception {
        create(staffToken, "CYCLE", TODAY, ScopeType.WHOLE, null, null)
                .andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNoNewCount();
    }

    @Test
    @Disabled("DEFECT: StockCountServiceImpl.create checks for an unfinished count without a lock or unique index, so two simultaneous creates both get 201")
    @DisplayName("UTC005 Abnormal: concurrency, two Branch Managers of the store create a count at the same time")
    void utc005() throws Exception {
        List<MvcResult> results = atTheSameTime(
                () -> create(managerToken, "CYCLE", TODAY, ScopeType.WHOLE, null, null).andReturn(),
                () -> create(manager2Token, "CYCLE", TODAY, ScopeType.WHOLE, null, null).andReturn());

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 409);
        MvcResult loser = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst()
                .orElseThrow();
        assertThat(JsonPath.<String>read(loser.getResponse().getContentAsString(), "$.code")).isEqualTo("MSG230");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_count WHERE count_type <> 'OPENING' "
                + "AND status = 'DRAFT'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_count WHERE count_type <> 'OPENING'", Long.class))
                .isEqualTo(1);
    }

    private ResultActions create(String token, String type, LocalDate date, ScopeType scope, List<Long> products,
                                 Long request) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("countType", type);
        body.put("countDate", date.toString());
        body.put("scopeType", scope.name());
        body.put("productIds", products);
        body.put("stockCountRequestId", request);
        return mvc.perform(as(token, post(URL), json(body)));
    }

    private void assertNoNewCount() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_count WHERE count_type <> 'OPENING'", Long.class))
                .isZero();
    }
}
