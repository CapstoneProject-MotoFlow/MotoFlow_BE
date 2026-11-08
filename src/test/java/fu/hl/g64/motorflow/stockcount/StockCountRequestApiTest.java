package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-105. */
class StockCountRequestApiTest extends StockCountTestData {

    private static final String URL = "/api/v1/stock-count-requests";

    private ResultActions create(String token, Long storeId, ScopeType scope, String note, String reason,
                                 List<Long> productIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeId", storeId);
        body.put("scopeType", scope);
        body.put("scopeNote", note);
        body.put("reason", reason);
        body.put("productIds", productIds);
        return postAs(token, URL, body);
    }

    private int requestCount() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_count_request", Integer.class);
    }

    @Test
    void ownerRequestsTheWholeWarehouseAndTheManagerIsTold() throws Exception {
        create(ownerToken, store, ScopeType.WHOLE, null, "  Kiểm định kỳ  ", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.scopeType").value("WHOLE"))
                .andExpect(jsonPath("$.scopeNote").doesNotExist())
                .andExpect(jsonPath("$.reason").value("Kiểm định kỳ"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestedBy").value(owner.getId()))
                .andExpect(jsonPath("$.products").isEmpty());

        long id = id("SELECT id FROM stock_count_request");
        assertThat(requestStatus(id)).isEqualTo("PENDING");
        assertThat(notificationsOf(manager)).containsExactly(List.of("STOCK_COUNT_REQUESTED", "STOCK_COUNT_REQUEST", id));
        assertThat(notificationsOf(manager2)).hasSize(1);
        assertThat(notificationsOf(otherManager)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT message FROM notification WHERE recipient_id = ?", String.class,
                manager.getId())).contains("Gia Lâm").contains("#" + id);
    }

    @Test
    void ownerRequestsSomePartsAndAnArea() throws Exception {
        create(ownerToken, store, ScopeType.PRODUCTS, "Kệ B", "Nghi thiếu", List.of(filter, oil))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create(ownerToken, store, ScopeType.PRODUCTS, null, "Nghi thiếu", List.of(filter, oil))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.products[*].productId", contains((int) oil, (int) filter)));
        assertThat(jdbc.queryForList("SELECT product_id FROM stock_count_request_product", Long.class))
                .containsExactlyInAnyOrder(oil, filter);

        create(ownerToken, store, ScopeType.AREA, " Kệ B ", "Kiểm khu B", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopeType").value("AREA"))
                .andExpect(jsonPath("$.scopeNote").value("Kệ B"));
        assertThat(jdbc.queryForObject("SELECT scope_note FROM stock_count_request WHERE scope_type = 'AREA'",
                String.class)).isEqualTo("Kệ B");
        assertThat(requestCount()).isEqualTo(2);
    }

    @Test
    void invalidScopeDataIsRejectedWithMsg02() throws Exception {
        create(ownerToken, store, ScopeType.PRODUCTS, null, "x", List.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create(ownerToken, store, ScopeType.PRODUCTS, null, "x", List.of(oil, oil)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create(ownerToken, store, ScopeType.AREA, null, "x", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create(ownerToken, store, ScopeType.AREA, "Kệ Z", "x", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create(ownerToken, store, ScopeType.AREA, "Kệ B", "x", List.of(oil)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create(ownerToken, store, ScopeType.WHOLE, null, "x", List.of(oil)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create(ownerToken, store, ScopeType.WHOLE, "Kệ B", "x", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create(ownerToken, store, ScopeType.WHOLE, null, " ", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create(ownerToken, store, ScopeType.WHOLE, null, "x".repeat(256), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        assertThat(requestCount()).isZero();
    }

    @Test
    void anAreaOfAnotherStoreDoesNotCount() throws Exception {
        jdbc.update("INSERT INTO stock_balance (store_id, product_id, available_qty, storage_area) VALUES (?, ?, 0, 'Kệ Z')",
                otherStore, oil);
        create(ownerToken, store, ScopeType.AREA, "Kệ Z", "x", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create(ownerToken, otherStore, ScopeType.AREA, "Kệ Z", "x", null).andExpect(status().isCreated());
    }

    @Test
    void onlyTheOwnerMayCreate() throws Exception {
        create(managerToken, store, ScopeType.WHOLE, null, "x", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        create(staffToken, store, ScopeType.WHOLE, null, "x", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        assertThat(requestCount()).isZero();
        assertThat(notificationsOf(manager)).isEmpty();
    }

    @Test
    void anInactiveStoreOrUnknownStoreOrPartIsRefusedWithMsg242() throws Exception {
        create(ownerToken, inactiveStore, ScopeType.WHOLE, null, "x", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG242"));
        create(ownerToken, 99_999L, ScopeType.WHOLE, null, "x", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG242"));
        create(ownerToken, store, ScopeType.PRODUCTS, null, "x", List.of(oil, 99_999L)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG242"));
        assertThat(requestCount()).isZero();
    }

    @Test
    void aClosedPeriodBlocksTheRequest() throws Exception {
        closePeriod(period);
        create(ownerToken, store, ScopeType.WHOLE, null, "x", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG232"));
        assertThat(requestCount()).isZero();
    }

    @Test
    void theCurrentMonthIsOpenedWhenItDoesNotExistYet() throws Exception {
        today(LocalDate.of(2026, 11, 3));
        create(ownerToken, store, ScopeType.WHOLE, null, "x", null).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_period WHERE period_month = '2026-11-01'",
                String.class)).isEqualTo("OPEN");
    }

    @Test
    void ownerCancelsAPendingRequestAndTheManagerIsTold() throws Exception {
        long request = seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.PENDING);

        postAs(ownerToken, URL + "/" + request + "/cancel", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        assertThat(requestStatus(request)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancelled_at IS NOT NULL FROM stock_count_request WHERE id = ?",
                Boolean.class, request)).isTrue();
        assertThat(notificationsOf(manager)).containsExactly(
                List.of("STOCK_COUNT_REQUEST_CANCELLED", "STOCK_COUNT_REQUEST", request));
        assertThat(jdbc.queryForObject("SELECT message FROM notification WHERE recipient_id = ?", String.class,
                manager.getId())).contains("Gia Lâm").contains("#" + request);
    }

    @Test
    void cancelGuardsRunInOrder() throws Exception {
        long done = seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.DONE);
        long cancelled = seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.CANCELLED);

        postAs(ownerToken, URL + "/99999/cancel", null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG246"));
        postAs(managerToken, URL + "/" + done + "/cancel", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        postAs(staffToken, URL + "/" + done + "/cancel", null).andExpect(status().isForbidden());
        postAs(ownerToken, URL + "/" + done + "/cancel", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG233"));
        postAs(ownerToken, URL + "/" + cancelled + "/cancel", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG233"));

        assertThat(requestStatus(done)).isEqualTo("DONE");
        assertThat(notificationsOf(manager)).isEmpty();
    }

    @Test
    void theBranchManagerSeesOnlyTheirStoresRequests() throws Exception {
        long mine = seedRequest(store, ScopeType.PRODUCTS, null, StockCountRequestStatus.PENDING, oil);
        long done = seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.DONE);
        long theirs = seedRequest(otherStore, ScopeType.WHOLE, null, StockCountRequestStatus.PENDING);

        getAs(managerToken, URL).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains((int) done, (int) mine)))
                .andExpect(jsonPath("$[1].products[0].sku").value("NHOT-01"));
        getAs(managerToken, URL + "?storeId=" + store + "&status=PENDING").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains((int) mine)));
        getAs(managerToken, URL + "?storeId=" + otherStore).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        getAs(staffToken, URL).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));

        getAs(ownerToken, URL).andExpect(jsonPath("$[*].id", contains((int) theirs, (int) done, (int) mine)));
        getAs(ownerToken, URL + "?storeId=" + otherStore).andExpect(jsonPath("$[*].id", contains((int) theirs)));
        getAs(ownerToken, URL + "?status=DONE").andExpect(jsonPath("$[*].id", contains((int) done)));
    }

    @Test
    void ownerListsTheActiveStoresByName() throws Exception {
        getAs(ownerToken, URL + "/stores").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].storeName", contains("Gia Lâm", "Long Biên")))
                .andExpect(jsonPath("$[0].id").value(store));
        getAs(managerToken, URL + "/stores").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        getAs(staffToken, URL + "/stores").andExpect(status().isForbidden());
    }
}
