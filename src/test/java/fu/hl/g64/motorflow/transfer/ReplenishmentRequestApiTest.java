package fu.hl.g64.motorflow.transfer;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-68 to UC-70. */
class ReplenishmentRequestApiTest extends ApiTestUsers {

    private static final String REQUESTS = "/api/v1/replenishment-requests";

    private UserAccount owner;
    private UserAccount centralManager;
    private UserAccount branchManager;
    private long central;
    private long branch;
    private long brake;
    private long chain;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        central = createStore("Kho tổng");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        branch = createStore("Long Biên");
        owner = createUser("owner", Role.OWNER, null);
        createUser("admin", Role.ADMIN, null);
        centralManager = createUser("bmkt", Role.BRANCH_MANAGER, central);
        branchManager = createUser("bm", Role.BRANCH_MANAGER, branch);
        createUser("staff", Role.STAFF, branch);
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        brake = product("MA-PHANH", "Má phanh trước", category);
        chain = product("XICH-XE", "Xích tải", category);
    }

    @Test
    void branchManagerCreatesForTheirStoreAndCoordinatorsAreTold() throws Exception {
        create("bm", null, " Sắp hết hàng ", line(brake, 5), line(chain, 2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestingStoreId").value(branch))
                .andExpect(jsonPath("$.storeName").value("Long Biên"))
                .andExpect(jsonPath("$.status").value("AWAITING_COORDINATION"))
                .andExpect(jsonPath("$.note").value("Sắp hết hàng"))
                .andExpect(jsonPath("$.createdBy").value(branchManager.getId()))
                .andExpect(jsonPath("$.lines[*].sku", contains("MA-PHANH", "XICH-XE")))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(5))
                .andExpect(jsonPath("$.lines[0].toPurchaseQty").value(0))
                .andExpect(jsonPath("$.lines[0].remainingQty").value(5));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM replenishment_request_line WHERE product_id IN (?, ?) "
                + "AND to_purchase_qty = 0", Long.class, brake, chain)).isEqualTo(2);
        assertThat(recipients()).containsExactlyInAnyOrder(owner.getId(), centralManager.getId());
        assertThat(jdbc.queryForObject("SELECT message FROM notification LIMIT 1", String.class))
                .isEqualTo("Cửa hàng Long Biên yêu cầu bổ sung 2 phụ tùng (yêu cầu #1).");
    }

    @Test
    void branchManagerCannotRequestForAnotherStore() throws Exception {
        create("bm", central, null, line(brake, 1)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        create("bmkt", branch, null, line(brake, 1)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
    }

    @Test
    void onlyTheOwnerStocksAPlannedStoreAndNobodyAnInactiveOne() throws Exception {
        jdbc.update("UPDATE store SET status = 'PLANNED' WHERE id = ?", branch);
        create("owner", branch, null, line(brake, 10)).andExpect(status().isCreated());
        create("bm", null, null, line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG46"));

        jdbc.update("UPDATE store SET status = 'INACTIVE' WHERE id = ?", branch);
        create("owner", branch, null, line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG46"));
        create("bm", null, null, line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG46"));
        create("owner", 9999L, null, line(brake, 1)).andExpect(jsonPath("$.code").value("MSG46"));
    }

    @Test
    void ownerMustChooseAStore() throws Exception {
        create("owner", null, null, line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
    }

    @Test
    void theCentralWarehouseMayRequestToo() throws Exception {
        create("bmkt", null, null, line(brake, 1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestingStoreId").value(central));
        assertThat(recipients()).containsExactly(owner.getId());

        jdbc.update("DELETE FROM notification");
        create("owner", central, null, line(chain, 1)).andExpect(status().isCreated());
        assertThat(recipients()).containsExactly(centralManager.getId());
    }

    @Test
    void linesAreChecked() throws Exception {
        create("bm", null, null, line(brake, 1), line(brake, 2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG93"));
        create("bm", null, null, line(brake, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create("bm", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create("bm", null, "x".repeat(501), line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        create("bm", null, null, line(9999, 1)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG57"));

        jdbc.update("UPDATE product SET status = 'DISCONTINUED', discontinued_at = now() WHERE id = ?", chain);
        create("bm", null, null, line(chain, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG94"))
                .andExpect(jsonPath("$.message").value("Phụ tùng XICH-XE đã ngừng kinh doanh."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM replenishment_request", Long.class)).isZero();
    }

    @Test
    void staffAndAdminAreRefused() throws Exception {
        create("staff", null, null, line(brake, 1)).andExpect(status().isForbidden());
        create("admin", branch, null, line(brake, 1)).andExpect(status().isForbidden());
    }

    @Test
    void partsBelowThresholdAreSuggestedWithWhatIsMissing() throws Exception {
        stock(branch, chain, 7, 10);
        stock(branch, brake, 0, 4);

        suggestions("bm", null).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("MA-PHANH", "XICH-XE")))
                .andExpect(jsonPath("$[1].productId").value(chain))
                .andExpect(jsonPath("$[1].partName").value("Xích tải"))
                .andExpect(jsonPath("$[1].baseUnit").value("cái"))
                .andExpect(jsonPath("$[1].availableQty").value(7))
                .andExpect(jsonPath("$[1].lowStockThreshold").value(10))
                .andExpect(jsonPath("$[1].suggestedQty").value(3));
    }

    @Test
    void stockAtThresholdNoThresholdAndDiscontinuedPartsAreNotSuggested() throws Exception {
        stock(branch, brake, 5, 5);
        stock(branch, chain, 0, null);
        long oil = product("DAU-NHOT", "Dầu nhớt", categoryOf(brake));
        stock(branch, oil, 0, 2);
        jdbc.update("UPDATE product SET status = 'DISCONTINUED', discontinued_at = now() WHERE id = ?", oil);

        suggestions("bm", null).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    @Test
    void aPartWaitingInAnOpenRequestOfTheStoreIsSkippedUntilItCloses() throws Exception {
        stock(branch, brake, 1, 5);
        stock(central, brake, 1, 5);
        create("bm", null, null, line(brake, 4)).andExpect(status().isCreated());

        suggestions("bm", null).andExpect(jsonPath("$", empty()));
        jdbc.update("UPDATE replenishment_request SET status = 'IN_PROGRESS'");
        suggestions("bm", null).andExpect(jsonPath("$", empty()));
        suggestions("owner", central).andExpect(jsonPath("$[*].sku", contains("MA-PHANH")));

        jdbc.update("UPDATE replenishment_request SET status = 'CLOSED', closed_at = now()");
        suggestions("bm", null).andExpect(jsonPath("$[*].sku", contains("MA-PHANH")));
    }

    @Test
    void suggestionsFollowTheStoreRulesOfCreating() throws Exception {
        stock(branch, brake, 1, 5);

        suggestions("bm", branch).andExpect(status().isOk());
        suggestions("bm", central).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        suggestions("bmkt", branch).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        suggestions("owner", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
        suggestions("owner", branch).andExpect(jsonPath("$[*].sku", contains("MA-PHANH")));

        jdbc.update("UPDATE store SET status = 'PLANNED' WHERE id = ?", branch);
        suggestions("owner", branch).andExpect(status().isOk());
        suggestions("bm", null).andExpect(jsonPath("$.code").value("MSG46"));

        jdbc.update("UPDATE store SET status = 'INACTIVE' WHERE id = ?", branch);
        suggestions("owner", branch).andExpect(jsonPath("$.code").value("MSG46"));

        suggestions("staff", null).andExpect(status().isForbidden());
        suggestions("admin", branch).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------------------

    @SafeVarargs
    private ResultActions create(String username, Long storeId, String note, Map<String, Object>... lines) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("storeId", storeId);
        body.put("note", note);
        body.put("lines", List.of(lines));
        return mvc.perform(post(REQUESTS).header(HttpHeaders.AUTHORIZATION, signIn(username))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions suggestions(String username, Long storeId) throws Exception {
        var request = get(REQUESTS + "/low-stock-suggestions").header(HttpHeaders.AUTHORIZATION, signIn(username));
        return mvc.perform(storeId == null ? request : request.param("storeId", storeId.toString()));
    }

    private void stock(long storeId, long productId, int available, Integer threshold) {
        jdbc.update("INSERT INTO stock_balance (store_id, product_id, available_qty, low_stock_threshold) "
                + "VALUES (?, ?, ?, ?)", storeId, productId, available, threshold);
    }

    private long categoryOf(long productId) {
        return jdbc.queryForObject("SELECT category_id FROM product WHERE id = ?", Long.class, productId);
    }

    private static Map<String, Object> line(long productId, int requestedQty) {
        return Map.of("productId", productId, "requestedQty", requestedQty);
    }

    private List<Long> recipients() {
        return jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = "
                + "'REPLENISHMENT_REQUESTED' AND ref_type = 'REPLENISHMENT_REQUEST'", Long.class);
    }

    private long product(String sku, String name, long category) {
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?) RETURNING id", Long.class, sku, name, category, owner.getId());
    }
}
