package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-90, UC-92 and the cancellation of UC-94. */
class StockCountViewApiTest extends StockCountTestData {

    private static final String URL = "/api/v1/stock-counts";

    private long countWithLines(StockCountStatus status) {
        return count(status).line(oilLine(12, 10)).line(filterLotALine(4, 4)).insert();
    }

    /** The completed opening count the fixture seeds lots from, moved to the oldest place of the list. */
    private long oldOpeningCount() {
        long opening = id("SELECT id FROM stock_count WHERE count_type = 'OPENING'");
        jdbc.update("UPDATE stock_count SET created_at = '2020-01-01' WHERE id = ?", opening);
        return opening;
    }

    // ------------------------------------------------------------------------------------------------ UC-90

    @Test
    void ownerListsEveryStoreNewestFirstAndFilters() throws Exception {
        long opening = oldOpeningCount();
        long first = count(StockCountStatus.DRAFT).insert();
        long second = count(StockCountStatus.AWAITING_APPROVAL).store(otherStore).insert();
        long third = count(StockCountStatus.COMPLETED).type(CountType.YEAR_END).insert();
        jdbc.update("UPDATE stock_count SET created_at = created_at - interval '1 day' WHERE id = ?", first);

        getAs(ownerToken, URL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.items[*].id", contains((int) third, (int) second, (int) first, (int) opening)))
                .andExpect(jsonPath("$.counts.items[1].storeName").value("Long Biên"))
                .andExpect(jsonPath("$.counts.totalItems").value(4))
                .andExpect(jsonPath("$.pendingRequests", hasSize(0)));
        getAs(ownerToken, URL + "?status=AWAITING_APPROVAL")
                .andExpect(jsonPath("$.counts.items[*].id", contains((int) second)));
        getAs(ownerToken, URL + "?storeId=" + store + "&countType=YEAR_END")
                .andExpect(jsonPath("$.counts.items[*].id", contains((int) third)));
        getAs(ownerToken, URL + "?from=2026-10-14")
                .andExpect(jsonPath("$.counts.items", hasSize(0)));
        getAs(ownerToken, URL + "?from=2026-10-13&to=2026-10-13&size=2&page=1")
                .andExpect(jsonPath("$.counts.items", hasSize(1)))
                .andExpect(jsonPath("$.counts.totalItems").value(3));
    }

    @Test
    void managerListsOnlyTheirWarehouseWithPendingRequests() throws Exception {
        long opening = oldOpeningCount();
        long mine = count(StockCountStatus.DRAFT).insert();
        count(StockCountStatus.DRAFT).store(otherStore).insert();
        long pending = seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.PENDING);
        seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.DONE);
        seedRequest(otherStore, ScopeType.WHOLE, null, StockCountRequestStatus.PENDING);

        getAs(managerToken, URL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.items[*].id", contains((int) mine, (int) opening)))
                .andExpect(jsonPath("$.pendingRequests[*].id", contains((int) pending)))
                .andExpect(jsonPath("$.pendingRequests[0].storeName").value("Gia Lâm"));
        getAs(managerToken, URL + "?storeId=" + store)
                .andExpect(jsonPath("$.counts.items", hasSize(2)));
        getAs(managerToken, URL + "?storeId=" + otherStore)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
    }

    @Test
    void staffListsOnlyTheCountsTheyAreCounting() throws Exception {
        long mine = count(StockCountStatus.COUNTING).task(staff, WarehouseTaskStatus.ASSIGNED).insert();
        long submitted = count(StockCountStatus.COUNTING).task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
        count(StockCountStatus.COUNTING).task(staff2, WarehouseTaskStatus.ASSIGNED).insert();
        count(StockCountStatus.CANCELLED).task(staff, WarehouseTaskStatus.CANCELLED).insert();
        count(StockCountStatus.DRAFT).insert();
        seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.PENDING);

        getAs(staffToken, URL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.items[*].id", contains((int) submitted, (int) mine)))
                .andExpect(jsonPath("$.counts.totalItems").value(2))
                .andExpect(jsonPath("$.pendingRequests", hasSize(0)));
    }

    @Test
    void anEmptyListIsNotAnError() throws Exception {
        getAs(otherManagerToken, URL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.items", hasSize(0)))
                .andExpect(jsonPath("$.counts.totalItems").value(0))
                .andExpect(jsonPath("$.pendingRequests", hasSize(0)));
    }

    // ------------------------------------------------------------------------------------------------ UC-92

    @Test
    void ownerSeesBookQuantitiesDifferencesAndTheirValue() throws Exception {
        long id = countWithLines(StockCountStatus.AWAITING_APPROVAL);

        getAs(ownerToken, URL + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].partName").value("Nhớt 10W40"))
                .andExpect(jsonPath("$.lines[0].bookQty").value(12))
                .andExpect(jsonPath("$.lines[0].countedQty").value(10))
                .andExpect(jsonPath("$.lines[0].difference").value(-2))
                .andExpect(jsonPath("$.lines[0].differenceValue").value(-170000))
                .andExpect(jsonPath("$.lines[1].difference").value(0));
    }

    @Test
    void managerSeesDifferencesButNoValue() throws Exception {
        long id = countWithLines(StockCountStatus.AWAITING_APPROVAL);

        getAs(managerToken, URL + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].bookQty").value(12))
                .andExpect(jsonPath("$.lines[0].difference").value(-2))
                .andExpect(jsonPath("$.lines[0].differenceValue").doesNotExist());
    }

    @Test
    void theCounterSeesNeitherBookQuantityNorDifference() throws Exception {
        long id = count(StockCountStatus.COUNTING).line(oilLine(12, 10)).line(filterLotALine(4, null))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();

        getAs(staffToken, URL + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].partName").value("Nhớt 10W40"))
                .andExpect(jsonPath("$.lines[0].storageArea").value("Kệ A"))
                .andExpect(jsonPath("$.lines[0].countedQty").value(10))
                .andExpect(jsonPath("$.lines[0].bookQty").doesNotExist())
                .andExpect(jsonPath("$.lines[0].difference").doesNotExist())
                .andExpect(jsonPath("$.lines[0].differenceValue").doesNotExist())
                .andExpect(jsonPath("$.lines[1].bookQty").doesNotExist())
                .andExpect(jsonPath("$.lines[1].countedQty").doesNotExist());
    }

    @Test
    void theDetailCarriesTheLinkedRequest() throws Exception {
        long request = seedRequest(store, ScopeType.PRODUCTS, null, StockCountRequestStatus.DONE, oil);
        long id = count(StockCountStatus.DRAFT).scope(ScopeType.PRODUCTS, null).request(request)
                .line(oilLine(12, null)).insert();

        getAs(managerToken, URL + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.id").value(request))
                .andExpect(jsonPath("$.request.reason").value("Kiểm định kỳ"))
                .andExpect(jsonPath("$.request.scopeType").value("PRODUCTS"))
                .andExpect(jsonPath("$.request.products[0].productId").value(oil));
    }

    @Test
    void otherPeopleAreRefused() throws Exception {
        long id = countWithLines(StockCountStatus.COUNTING);
        count(StockCountStatus.COUNTING).task(staff, WarehouseTaskStatus.ASSIGNED).insert();
        long withTask = id("SELECT max(id) FROM stock_count");

        getAs(staff2Token, URL + "/" + id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG238"));
        getAs(staff2Token, URL + "/" + withTask)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG238"));
        getAs(otherManagerToken, URL + "/" + id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        getAs(staffToken, URL + "/" + withTask).andExpect(status().isOk());
        getAs(ownerToken, URL + "/" + 999_999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG245"));
    }

    // ------------------------------------------------------------------------------------------------ UC-94 cancel

    private Map<String, String> reason(String text) {
        return Map.of("reason", text);
    }

    @Test
    void managerCancelsADraftWithoutBotheringTheOwner() throws Exception {
        long id = count(StockCountStatus.DRAFT).task(staff, WarehouseTaskStatus.ASSIGNED).insert();

        postAs(managerToken, URL + "/" + id + "/cancel", reason("  Nhập nhầm  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("Nhập nhầm"))
                .andExpect(jsonPath("$.cancelledBy").value(manager.getId()));

        assertThat(countStatus(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancelled_by FROM stock_count WHERE id = ?", Long.class, id))
                .isEqualTo(manager.getId());
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM stock_count WHERE id = ?", Object.class, id))
                .isNotNull();
        assertThat(taskStatuses(id)).containsExactly("CANCELLED");
        assertThat(notificationsOf(owner)).isEmpty();
    }

    @Test
    void cancellingACountWithCountedLinesTellsTheOwnerAndKeepsTheRequestDone() throws Exception {
        long request = seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.DONE);
        long id = count(StockCountStatus.COUNTING).request(request).line(oilLine(12, 10))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();

        postAs(managerToken, URL + "/" + id + "/cancel", reason("Đếm sai kho"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(taskStatuses(id)).containsExactly("CANCELLED");
        assertThat(requestStatus(request)).isEqualTo("DONE");
        assertThat(notificationsOf(owner)).containsExactly(List.of("STOCK_COUNT_CANCELLED", "STOCK_COUNT", id));
        assertThat(jdbc.queryForObject("SELECT message FROM notification WHERE recipient_id = ?", String.class,
                owner.getId())).contains("Gia Lâm").contains("#" + id);
    }

    @Test
    void aCountWithCountedLinesAndNoRequestAlsoTellsTheOwner() throws Exception {
        long id = count(StockCountStatus.COUNTING).line(oilLine(12, 10)).insert();

        postAs(managerToken, URL + "/" + id + "/cancel", reason("Đếm sai kho")).andExpect(status().isOk());

        assertThat(notificationsOf(owner)).hasSize(1);
    }

    @Test
    void aCountAlreadySentToTheOwnerCannotBeCancelled() throws Exception {
        long id = count(StockCountStatus.COUNTING).confirmedOnce().line(oilLine(12, 10))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();

        postAs(managerToken, URL + "/" + id + "/cancel", reason("Hủy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG243"));

        assertThat(countStatus(id)).isEqualTo("COUNTING");
        assertThat(taskStatuses(id)).containsExactly("ASSIGNED");
        assertThat(notificationsOf(owner)).isEmpty();
    }

    @Test
    void finishedCountsCannotBeCancelled() throws Exception {
        for (StockCountStatus finished : List.of(StockCountStatus.AWAITING_APPROVAL, StockCountStatus.COMPLETED,
                StockCountStatus.CANCELLED)) {
            long id = count(finished).insert();
            postAs(managerToken, URL + "/" + id + "/cancel", reason("Hủy"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MSG235"));
            assertThat(countStatus(id)).isEqualTo(finished.name());
        }
    }

    @Test
    void onlyTheManagerOfTheWarehouseMayCancel() throws Exception {
        long id = count(StockCountStatus.DRAFT).insert();

        for (String token : List.of(ownerToken, otherManagerToken, staffToken)) {
            postAs(token, URL + "/" + id + "/cancel", reason("Hủy"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MSG05"));
        }
        postAs(managerToken, URL + "/999999/cancel", reason("Hủy"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG245"));
        postAs(managerToken, URL + "/" + id + "/cancel", Map.of("reason", " "))
                .andExpect(status().isBadRequest());
        assertThat(countStatus(id)).isEqualTo("DRAFT");
    }
}
