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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
        mvc.perform(post(REQUESTS).header(HttpHeaders.AUTHORIZATION, signIn("bm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        mvc.perform(post(REQUESTS).header(HttpHeaders.AUTHORIZATION, signIn("bm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"productId\":" + brake + ",\"requestedQty\":1.5}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        mvc.perform(post(REQUESTS).header(HttpHeaders.AUTHORIZATION, signIn("bm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lines", Collections.nCopies(201, line(brake, 1))))))
                .andExpect(status().isBadRequest())
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

    @Test
    void aBranchManagerListsTheirStoreAndCoordinatorsListEveryStore() throws Exception {
        long own = created("bm", null);
        long other = created("owner", central);

        list("bm").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains((int) own)))
                .andExpect(jsonPath("$.totalItems").value(1));
        list("bmkt").andExpect(jsonPath("$.items[*].id", containsInAnyOrder((int) own, (int) other)));
        list("owner").andExpect(jsonPath("$.items[*].id", containsInAnyOrder((int) own, (int) other)));

        list("bm", "storeId", String.valueOf(central)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        list("bm", "storeId", String.valueOf(branch)).andExpect(jsonPath("$.items[*].id", contains((int) own)));
        list("staff").andExpect(status().isForbidden());
        list("admin").andExpect(status().isForbidden());
    }

    @Test
    void aRowCarriesTheTotalsOfItsLines() throws Exception {
        create("bm", null, null, line(brake, 5), line(chain, 2)).andExpect(status().isCreated());

        list("owner").andExpect(jsonPath("$.items[0].requestingStoreId").value(branch))
                .andExpect(jsonPath("$.items[0].storeName").value("Long Biên"))
                .andExpect(jsonPath("$.items[0].status").value("AWAITING_COORDINATION"))
                .andExpect(jsonPath("$.items[0].lineCount").value(2))
                .andExpect(jsonPath("$.items[0].totalRequestedQty").value(7))
                .andExpect(jsonPath("$.items[0].createdBy").value(branchManager.getId()))
                .andExpect(jsonPath("$.items[0].createdByName").value(branchManager.getFullName()))
                .andExpect(jsonPath("$.items[0].closedAt").isEmpty());
    }

    @Test
    void requestsAwaitingCoordinationComeFirstThenTheNewest() throws Exception {
        long oldOpen = created("bm", null);
        long closed = created("bm", null);
        long newOpen = created("bm", null);
        long progressing = created("bm", null);
        at(oldOpen, "2026-10-01 09:00:00");
        at(closed, "2026-10-05 09:00:00");
        at(newOpen, "2026-10-03 09:00:00");
        at(progressing, "2026-10-04 09:00:00");
        jdbc.update("UPDATE replenishment_request SET status = 'CLOSED', closed_at = now() WHERE id = ?", closed);
        jdbc.update("UPDATE replenishment_request SET status = 'IN_PROGRESS' WHERE id = ?", progressing);

        list("owner").andExpect(jsonPath("$.items[*].id",
                contains((int) newOpen, (int) oldOpen, (int) closed, (int) progressing)));
    }

    @Test
    void theListIsFilteredByStatusStoreAndCreatedDate() throws Exception {
        long first = created("bm", null);
        long second = created("owner", central);
        long third = created("bm", null);
        at(first, "2026-10-01 23:30:00");
        at(second, "2026-10-02 00:00:00");
        at(third, "2026-10-03 08:00:00");
        jdbc.update("UPDATE replenishment_request SET status = 'IN_PROGRESS' WHERE id = ?", third);

        list("owner", "status", "IN_PROGRESS").andExpect(jsonPath("$.items[*].id", contains((int) third)));
        list("owner", "storeId", String.valueOf(central)).andExpect(jsonPath("$.items[*].id", contains((int) second)));
        list("owner", "from", "2026-10-02").andExpect(jsonPath("$.totalItems").value(2));
        list("owner", "to", "2026-10-01").andExpect(jsonPath("$.items[*].id", contains((int) first)));
        list("owner", "from", "2026-10-01", "to", "2026-10-02")
                .andExpect(jsonPath("$.items[*].id", contains((int) second, (int) first)));
    }

    @Test
    void theDetailAddsUpTransfersAndConfirmedReceipts() throws Exception {
        long id = created("bm", null, line(brake, 10), line(chain, 2));
        jdbc.update("UPDATE replenishment_request_line SET to_purchase_qty = 3 WHERE product_id = ?", brake);
        long other = createStore("Cầu Giấy");
        long first = transfer(central, branch, id, line(brake, 4), line(chain, 2));
        long second = transfer(other, branch, id, line(brake, 2));
        transfer(central, branch, null, line(brake, 50));

        long stray = transfer(central, branch, null, line(brake, 7));
        receipt(stray, "TRANSFER_IN", "CONFIRMED", brake, 7);
        receipt(first, "TRANSFER_IN", "CONFIRMED", brake, 3);
        receipt(first, "TRANSFER_IN", "CONFIRMED", chain, 5);
        // The receipt expected the 2 dispatched, so the other 3 are stored as excess, as recordResult does.
        jdbc.update("UPDATE goods_receipt_note_line SET document_qty = 2, excess_qty = 3 WHERE product_id = ? "
                + "AND goods_receipt_note_id = (SELECT max(id) FROM goods_receipt_note)", chain);
        receipt(second, "TRANSFER_IN", "AWAITING_CONFIRMATION", brake, 2);
        receipt(first, "RETURN_TO_SOURCE", "CONFIRMED", brake, 1);

        detail("bm", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Cần gấp"))
                .andExpect(jsonPath("$.createdByName").value(branchManager.getFullName()))
                .andExpect(jsonPath("$.lines[0].sku").value("MA-PHANH"))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(10))
                .andExpect(jsonPath("$.lines[0].plannedQty").value(6))
                .andExpect(jsonPath("$.lines[0].fulfilledQty").value(3))
                .andExpect(jsonPath("$.lines[0].toPurchaseQty").value(3))
                .andExpect(jsonPath("$.lines[0].remainingQty").value(4))
                .andExpect(jsonPath("$.lines[1].plannedQty").value(2))
                // 5 received against 2 dispatched: the 3 above are excess, not fulfilment.
                .andExpect(jsonPath("$.lines[1].fulfilledQty").value(2))
                .andExpect(jsonPath("$.lines[1].remainingQty").value(0))
                .andExpect(jsonPath("$.transferOrders[*].id", contains((int) first, (int) second)))
                .andExpect(jsonPath("$.transferOrders[1].sourceStoreId").value(other))
                .andExpect(jsonPath("$.transferOrders[1].sourceStoreName").value("Cầu Giấy"))
                .andExpect(jsonPath("$.transferOrders[1].destStoreId").value(branch))
                .andExpect(jsonPath("$.transferOrders[1].status").value("AWAITING_DISPATCH"));
    }

    @Test
    void purchaseOrdersOfTheRequestAreShownToWhoeverMayOpenIt() throws Exception {
        long id = created("bm", null);
        long supplier = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) "
                + "VALUES ('NCC001', 'Minh Phát') RETURNING id", Long.class);
        long order = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, store_id, replenishment_request_id, "
                + "created_by) VALUES (?, ?, ?, ?) RETURNING id", Long.class, supplier, branch, id, owner.getId());
        jdbc.update("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?)",
                supplier, branch, owner.getId());

        for (String reader : List.of("owner", "bmkt", "bm")) {
            detail(reader, id).andExpect(jsonPath("$.purchaseOrders[*].id", contains((int) order)))
                    .andExpect(jsonPath("$.purchaseOrders[0].supplierId").value(supplier))
                    .andExpect(jsonPath("$.purchaseOrders[0].supplierName").value("Minh Phát"))
                    .andExpect(jsonPath("$.purchaseOrders[0].status").value("AWAITING_DELIVERY"));
        }
    }

    @Test
    void theDetailIsRefusedOutsideTheStoreAndMissingRequestsAreNotFound() throws Exception {
        long id = created("owner", central);

        detail("bm", id).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        detail("bmkt", id).andExpect(status().isOk());
        detail("owner", 9999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG95"));
        detail("staff", id).andExpect(status().isForbidden());
        detail("admin", id).andExpect(status().isForbidden());
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

    @SafeVarargs
    private long created(String username, Long storeId, Map<String, Object>... lines) throws Exception {
        create(username, storeId, "Cần gấp", lines.length == 0 ? new Map[] {line(brake, 1)} : lines)
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT max(id) FROM replenishment_request", Long.class);
    }

    private void at(long requestId, String createdAt) {
        jdbc.update("UPDATE replenishment_request SET created_at = ?::timestamp WHERE id = ?", createdAt, requestId);
    }

    private ResultActions list(String username, String... params) throws Exception {
        var request = get(REQUESTS).header(HttpHeaders.AUTHORIZATION, signIn(username));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions detail(String username, long id) throws Exception {
        return mvc.perform(get(REQUESTS + "/" + id).header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    @SafeVarargs
    private long transfer(long source, long destination, Long requestId, Map<String, Object>... lines) {
        long id = jdbc.queryForObject("INSERT INTO transfer_order (replenishment_request_id, source_store_id, "
                + "dest_store_id, created_by) VALUES (?, ?, ?, ?) RETURNING id", Long.class, requestId, source,
                destination, owner.getId());
        for (Map<String, Object> line : lines) {
            jdbc.update("INSERT INTO transfer_order_line (transfer_order_id, product_id, planned_qty, dispatched_qty) "
                    + "VALUES (?, ?, ?, ?)", id, line.get("productId"), line.get("requestedQty"), line.get("requestedQty"));
        }
        return id;
    }

    private void receipt(long transferId, String type, String receiptStatus, long productId, int passed) {
        long receipt = jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, transfer_order_id, arrived_at, "
                + "status, recorded_by, submitted_at, confirmed_by, confirmed_at) VALUES (?, ?, now(), ?, ?, now(), ?, "
                + "now()) RETURNING id", Long.class, type, transferId, receiptStatus, owner.getId(),
                branchManager.getId());
        jdbc.update("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, delivered_qty, "
                + "passed_qty) VALUES (?, ?, ?, ?, ?)", receipt, productId, passed, passed, passed);
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
