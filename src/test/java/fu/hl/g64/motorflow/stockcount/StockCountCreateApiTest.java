package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-91. */
class StockCountCreateApiTest extends StockCountTestData {

    private static final String URL = "/api/v1/stock-counts";

    private Map<String, Object> body(String type, LocalDate date, ScopeType scope, String note, List<Long> products,
                                     Long request) {
        Map<String, Object> body = new HashMap<>();
        body.put("countType", type);
        body.put("countDate", date.toString());
        body.put("scopeType", scope.name());
        body.put("scopeNote", note);
        body.put("productIds", products);
        body.put("stockCountRequestId", request);
        return body;
    }

    private ResultActions create(String token, Map<String, Object> body) throws Exception {
        return postAs(token, URL, body);
    }

    private ResultActions cycle(ScopeType scope, String note, List<Long> products) throws Exception {
        return create(managerToken, body("CYCLE", TODAY, scope, note, products, null));
    }

    private ResultActions refused(ResultActions result, int httpStatus, String code) throws Exception {
        return result.andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code));
    }

    /** [product_id, lot_id, expiry_date, book_qty] of the count's lines. */
    private List<List<Object>> lines(long count) {
        return jdbc.queryForList("SELECT product_id, lot_id, expiry_date, book_qty FROM stock_count_line "
                + "WHERE stock_count_id = ? ORDER BY product_id, expiry_date NULLS LAST, id", count).stream()
                .map(r -> Arrays.asList(r.get("product_id"), r.get("lot_id"),
                        r.get("expiry_date") == null ? null : r.get("expiry_date").toString(), r.get("book_qty")))
                .toList();
    }

    private long draftCount() {
        return id("SELECT id FROM stock_count WHERE status = 'DRAFT'");
    }

    private long countRows() {
        return id("SELECT count(*) FROM stock_count WHERE count_type <> 'OPENING'");
    }

    @Test
    void wholeWarehouseSplitsExpiringPartsByLotAndGroupsTheRest() throws Exception {
        cycle(ScopeType.WHOLE, null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.storeId").value(store))
                .andExpect(jsonPath("$.inventoryPeriodId").value(period))
                .andExpect(jsonPath("$.createdBy").value(manager.getId()))
                .andExpect(jsonPath("$.lines.length()").value(4));

        long count = draftCount();
        assertThat(jdbc.queryForObject("SELECT created_by FROM stock_count WHERE id = ?", Long.class, count))
                .isEqualTo(manager.getId());
        assertThat(lines(count)).containsExactly(
                Arrays.asList(oil, null, null, 0),
                Arrays.asList(filter, filterLotA, "2027-01-31", 0),
                Arrays.asList(filter, filterLotB, "2027-06-30", 0),
                Arrays.asList(filter, null, null, 0));
    }

    @Test
    void areaTakesThePartsShelvedThere() throws Exception {
        cycle(ScopeType.AREA, "Kệ B", null).andExpect(status().isCreated());

        assertThat(lines(draftCount())).extracting(l -> l.get(0)).containsOnly(filter).hasSize(3);
    }

    @Test
    void productsGivesAPartWithoutStockOneLineWithNoLot() throws Exception {
        cycle(ScopeType.PRODUCTS, null, List.of(pad, oil)).andExpect(status().isCreated());

        assertThat(lines(draftCount())).containsExactly(
                Arrays.asList(oil, null, null, 0),
                Arrays.asList(pad, null, null, 0));
    }

    @Test
    void anotherCountOfTheWarehouseStillUnfinishedIsRefused() throws Exception {
        for (StockCountStatus unfinished : List.of(StockCountStatus.DRAFT, StockCountStatus.COUNTING,
                StockCountStatus.AWAITING_APPROVAL)) {
            long existing = count(unfinished).insert();
            refused(cycle(ScopeType.WHOLE, null, null), 409, "MSG230");
            jdbc.update("DELETE FROM stock_count WHERE id = ?", existing);
        }
        cycle(ScopeType.WHOLE, null, null).andExpect(status().isCreated());
    }

    @Test
    void theDateMustFallInAnOpenPeriodNotAfterThisMonth() throws Exception {
        closePeriod(period);
        refused(cycle(ScopeType.WHOLE, null, null), 409, "MSG232");
        refused(create(managerToken, body("CYCLE", LocalDate.of(2026, 11, 1), ScopeType.WHOLE, null, null, null)),
                409, "MSG232");
        // No period for September was ever created.
        refused(create(managerToken, body("CYCLE", LocalDate.of(2026, 9, 30), ScopeType.WHOLE, null, null, null)),
                409, "MSG232");
        assertThat(countRows()).isZero();
    }

    @Test
    void thisMonthsPeriodIsOpenedWhenMissing() throws Exception {
        today(LocalDate.of(2026, 11, 2));

        create(managerToken, body("CYCLE", LocalDate.of(2026, 11, 20), ScopeType.WHOLE, null, null, null))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT status FROM inventory_period WHERE period_month = '2026-11-01'",
                String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT inventory_period_id FROM stock_count WHERE count_type = 'CYCLE'",
                Long.class)).isEqualTo(id("SELECT id FROM inventory_period WHERE period_month = '2026-11-01'"));
    }

    @Test
    void answeringARequestMarksItDone() throws Exception {
        long request = seedRequest(store, ScopeType.PRODUCTS, "Kiểm gấp", StockCountRequestStatus.PENDING, oil, filter);

        create(managerToken, body("CYCLE", TODAY, ScopeType.PRODUCTS, "Kiểm gấp", List.of(filter, oil), request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.request.id").value(request));

        assertThat(requestStatus(request)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT stock_count_request_id FROM stock_count WHERE count_type = 'CYCLE'",
                Long.class)).isEqualTo(request);
    }

    @Test
    void aRequestThatIsNotPendingOrNotTheStoresIsRefused() throws Exception {
        long done = seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.DONE);
        long cancelled = seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.CANCELLED);
        long foreign = seedRequest(otherStore, ScopeType.WHOLE, null, StockCountRequestStatus.PENDING);

        for (long request : new long[]{done, cancelled}) {
            refused(create(managerToken, body("CYCLE", TODAY, ScopeType.WHOLE, null, null, request)), 409, "MSG233");
        }
        refused(create(managerToken, body("CYCLE", TODAY, ScopeType.WHOLE, null, null, foreign)), 403, "MSG05");
        refused(create(managerToken, body("CYCLE", TODAY, ScopeType.WHOLE, null, null, 999_999L)), 404, "MSG246");
        assertThat(countRows()).isZero();
    }

    @Test
    void aCountThatDiffersFromItsRequestIsRefused() throws Exception {
        long request = seedRequest(store, ScopeType.PRODUCTS, "Kiểm gấp", StockCountRequestStatus.PENDING, oil);

        List<Map<String, Object>> wrong = List.of(
                body("CYCLE", TODAY, ScopeType.WHOLE, null, null, request),
                body("CYCLE", TODAY, ScopeType.PRODUCTS, "Ghi chú khác", List.of(oil), request),
                body("CYCLE", TODAY, ScopeType.PRODUCTS, "Kiểm gấp", List.of(oil, filter), request),
                body("YEAR_END", TODAY, ScopeType.PRODUCTS, "Kiểm gấp", List.of(oil), request));
        for (Map<String, Object> each : wrong) {
            refused(create(managerToken, each), 409, "MSG234");
        }
        assertThat(requestStatus(request)).isEqualTo("PENDING");
        assertThat(countRows()).isZero();
    }

    @Test
    void onlyTheBranchManagerOfAWarehouseCreates() throws Exception {
        for (String token : List.of(ownerToken, staffToken)) {
            refused(create(token, body("CYCLE", TODAY, ScopeType.WHOLE, null, null, null)), 403, "MSG05");
        }
        assertThat(countRows()).isZero();
    }

    @Test
    void anOpeningCountIsNotSupportedYetAndYearEndIs() throws Exception {
        refused(create(managerToken, body("OPENING", TODAY, ScopeType.WHOLE, null, null, null)), 409, "MSG244");
        // Checked before the role: an Owner gets the same answer.
        refused(create(ownerToken, body("OPENING", TODAY, ScopeType.WHOLE, null, null, null)), 409, "MSG244");
        assertThat(countRows()).isZero();

        create(managerToken, body("YEAR_END", TODAY, ScopeType.WHOLE, null, null, null)).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count_type FROM stock_count WHERE status = 'DRAFT'", String.class))
                .isEqualTo("YEAR_END");
    }

    @Test
    void aScopeThatDoesNotFitItsTypeIsRefused() throws Exception {
        refused(cycle(ScopeType.WHOLE, null, List.of(oil)), 400, "MSG02");
        refused(cycle(ScopeType.PRODUCTS, null, List.of()), 400, "MSG02");
        refused(cycle(ScopeType.PRODUCTS, null, List.of(oil, oil)), 400, "MSG02");
        refused(cycle(ScopeType.AREA, null, null), 400, "MSG02");
        refused(cycle(ScopeType.AREA, "Kệ Z", null), 400, "MSG02");
        refused(cycle(ScopeType.AREA, "Kệ A", List.of(oil)), 400, "MSG02");
        refused(cycle(ScopeType.PRODUCTS, null, List.of(oil, 999_999L)), 400, "MSG242");
        assertThat(countRows()).isZero();
    }

    @Test
    void prefillShowsAPendingRequestToTheManagerOfItsStore() throws Exception {
        long request = seedRequest(store, ScopeType.PRODUCTS, null, StockCountRequestStatus.PENDING, oil);
        long done = seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.DONE);

        getAs(managerToken, URL + "/prefill?requestId=" + request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(request))
                .andExpect(jsonPath("$.scopeType").value("PRODUCTS"))
                .andExpect(jsonPath("$.products[0].productId").value(oil));
        refused(getAs(managerToken, URL + "/prefill?requestId=" + done), 409, "MSG233");
        refused(getAs(otherManagerToken, URL + "/prefill?requestId=" + request), 403, "MSG05");
        refused(getAs(ownerToken, URL + "/prefill?requestId=" + request), 403, "MSG05");
        refused(getAs(managerToken, URL + "/prefill?requestId=999999"), 404, "MSG246");
    }
}
