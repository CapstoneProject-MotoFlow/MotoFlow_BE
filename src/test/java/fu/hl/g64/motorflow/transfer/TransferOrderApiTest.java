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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-71 and BR-55. */
class TransferOrderApiTest extends ApiTestUsers {

    private static final String ORDERS = "/api/v1/transfer-orders";
    private static final String REQUESTS = "/api/v1/replenishment-requests";

    private UserAccount owner;
    private UserAccount centralManager;
    private UserAccount sourceManager;
    private UserAccount branchManager;
    private long central;
    private long source;
    private long branch;
    private long brake;
    private long chain;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        central = createStore("Kho tổng");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        source = createStore("Cầu Giấy");
        branch = createStore("Long Biên");
        owner = createUser("owner", Role.OWNER, null);
        centralManager = createUser("bmkt", Role.BRANCH_MANAGER, central);
        sourceManager = createUser("bmcg", Role.BRANCH_MANAGER, source);
        branchManager = createUser("bm", Role.BRANCH_MANAGER, branch);
        createUser("staff", Role.STAFF, branch);
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        brake = product("MA-PHANH", "Má phanh trước", category);
        chain = product("XICH-XE", "Xích tải", category);
        stock(source, brake, 20);
        stock(source, chain, 20);
        stock(central, brake, 20);
    }

    @Test
    void centralManagerCreatesFromRequestWithoutTouchingStock() throws Exception {
        long request = request(branch, Map.of(brake, 8));

        create("bmkt", request, source, branch, line(brake, 5))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_DISPATCH"))
                .andExpect(jsonPath("$.replenishmentRequestId").value(request))
                .andExpect(jsonPath("$.sourceStoreId").value(source))
                .andExpect(jsonPath("$.createdBy.id").value(centralManager.getId()))
                .andExpect(jsonPath("$.lines[0].plannedQty").value(5));

        long order = lastOrder();
        assertThat(jdbc.queryForObject("SELECT status FROM replenishment_request WHERE id = ?", String.class, request))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT available_qty FROM stock_balance WHERE store_id = ? AND product_id = ?",
                Integer.class, source, brake)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
        assertThat(recipients("TRANSFER_CREATED")).containsExactly(sourceManager.getId());
        assertThat(jdbc.queryForObject("SELECT message FROM notification WHERE notification_type = 'TRANSFER_CREATED'",
                String.class)).isEqualTo("Có phiếu chuyển kho #" + order + " cần chuẩn bị hàng gửi tới Long Biên");
        assertThat(jdbc.queryForObject("SELECT ref_type FROM notification WHERE notification_type = 'TRANSFER_CREATED'",
                String.class)).isEqualTo("TRANSFER_ORDER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'TRANSFER_ORDER' "
                + "AND entity_id = ? AND action = 'TRANSFER_ORDER_CREATED'", Long.class, order)).isEqualTo(1);
    }

    @Test
    void creatorSourcingFromTheirOwnStoreIsNotNotified() throws Exception {
        create("bmkt", null, central, branch, line(brake, 1)).andExpect(status().isCreated());

        assertThat(recipients("TRANSFER_CREATED")).isEmpty();
    }

    @Test
    void onlyCoordinatorsCreate() throws Exception {
        create("bm", null, source, branch, line(brake, 1))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        create("staff", null, source, branch, line(brake, 1))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        create("bmcg", null, source, branch, line(brake, 1))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        create("owner", null, source, branch, line(brake, 1)).andExpect(status().isCreated());
    }

    @Test
    void refusesInvalidRouteAndQuantities() throws Exception {
        long request = request(branch, Map.of(brake, 8));

        create("bmkt", null, source, source, line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG200"));
        create("bmkt", null, source, branch, line(brake, 21)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG201"));
        create("bmkt", null, source, branch, line(brake, 1), line(brake, 2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG93"));
        create("bmkt", request, source, central, line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG203"));
        create("bmkt", request, source, branch, line(chain, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG204"));

        create("bmkt", request, central, branch, line(brake, 6)).andExpect(status().isCreated());
        create("bmkt", request, source, branch, line(brake, 3)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG202"));
        create("bmkt", request, source, branch, line(brake, 2)).andExpect(status().isCreated());
    }

    @Test
    void expiredStockIsNotSellable() throws Exception {
        long count = jdbc.queryForObject("INSERT INTO stock_count (store_id, count_type, count_date, scope_type, "
                + "created_by) VALUES (?, 'OPENING', '2019-01-01', 'WHOLE', ?) RETURNING id", Long.class, source,
                owner.getId());
        long lot = jdbc.queryForObject("INSERT INTO lot (product_id, source_type, stock_count_id, store_id, "
                + "received_date, purchase_unit_price, expiry_date, status) VALUES (?, 'OPENING', ?, ?, '2019-01-01', "
                + "1000, '2020-01-01', 'EXPIRED') RETURNING id", Long.class, brake, count, source);
        jdbc.update("INSERT INTO lot_balance (lot_id, store_id, stock_status, quantity) VALUES (?, ?, 'AVAILABLE', 5)",
                lot, source);

        create("bmkt", null, source, branch, line(brake, 16)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG201"));
        create("bmkt", null, source, branch, line(brake, 15)).andExpect(status().isCreated());
    }

    @Test
    void onlyOwnerMayStockAPlannedStore() throws Exception {
        long planned = createStore("Sắp mở");
        jdbc.update("UPDATE store SET status = 'PLANNED' WHERE id = ?", planned);

        create("bmkt", null, source, planned, line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG46"));
        create("owner", null, source, planned, line(brake, 1)).andExpect(status().isCreated());
        create("owner", null, planned, branch, line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG46"));
    }

    @Test
    void makeUpShipmentNeedsAnOpenTransferCaseAndUsesNoDemand() throws Exception {
        long request = request(branch, Map.of(brake, 5));
        create("bmkt", request, source, branch, line(brake, 5)).andExpect(status().isCreated());
        long shortCase = jdbc.queryForObject("INSERT INTO discrepancy_case (source_type, transfer_order_id, product_id, "
                + "short_qty, opened_at) VALUES ('TRANSFER', ?, ?, 2, now()) RETURNING id", Long.class, lastOrder(), brake);

        create("bmkt", request, central, branch, makeup(brake, 3, shortCase)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG206"));
        create("bmkt", null, central, source, makeup(brake, 1, shortCase)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG206"));
        create("bmkt", request, central, branch, makeup(brake, 2, shortCase))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].makeupForCaseId").value(shortCase));
        create("bmkt", request, central, branch, line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG202"));
    }

    @Test
    void passesShortagesToTheOwner() throws Exception {
        long request = request(branch, Map.of(brake, 8, chain, 4));
        create("bmkt", request, source, branch, line(brake, 5)).andExpect(status().isCreated());

        toPurchase("bmkt", request, buy(brake, 4)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG205"));
        toPurchase("bmkt", request, buy(brake, 3), buy(chain, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        assertThat(jdbc.queryForObject("SELECT to_purchase_qty FROM replenishment_request_line "
                + "WHERE replenishment_request_id = ? AND product_id = ?", Integer.class, request, brake)).isEqualTo(3);
        assertThat(recipients("REPLENISHMENT_TO_PURCHASE")).containsExactly(owner.getId());
        assertThat(jdbc.queryForObject("SELECT message FROM notification WHERE notification_type = "
                + "'REPLENISHMENT_TO_PURCHASE'", String.class))
                .isEqualTo("Yêu cầu bổ sung #" + request + " có 2 phụ tùng cần mua từ nhà cung cấp");
        create("bmkt", request, central, branch, line(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG202"));
    }

    @Test
    void toPurchaseMovesTheRequestOnAndSparesTheOwnerTheirOwnNotice() throws Exception {
        long request = request(branch, Map.of(brake, 2));

        toPurchase("owner", request, buy(brake, 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        assertThat(recipients("REPLENISHMENT_TO_PURCHASE")).isEmpty();
    }

    @Test
    void toPurchaseGuards() throws Exception {
        long request = request(branch, Map.of(brake, 2));

        toPurchase("bm", request, buy(brake, 1)).andExpect(status().isForbidden());
        toPurchase("staff", request, buy(brake, 1)).andExpect(status().isForbidden());
        toPurchase("bmkt", 9999, buy(brake, 1)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG95"));
        toPurchase("bmkt", request, buy(chain, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG204"));
        toPurchase("bmkt", request, buy(brake, 1), buy(brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG93"));

        jdbc.update("UPDATE replenishment_request SET status = 'CLOSED', closed_at = now() WHERE id = ?", request);
        toPurchase("bmkt", request, buy(brake, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
        create("bmkt", request, source, branch, line(brake, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
    }

    // ------------------------------------------------------------------------------------------------------------

    @SafeVarargs
    private ResultActions create(String username, Long requestId, long from, long to, Map<String, Object>... lines)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("replenishmentRequestId", requestId);
        body.put("sourceStoreId", from);
        body.put("destStoreId", to);
        body.put("lines", List.of(lines));
        return mvc.perform(post(ORDERS).header(HttpHeaders.AUTHORIZATION, signIn(username))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    @SafeVarargs
    private ResultActions toPurchase(String username, long requestId, Map<String, Object>... lines) throws Exception {
        return mvc.perform(put(REQUESTS + "/" + requestId + "/to-purchase")
                .header(HttpHeaders.AUTHORIZATION, signIn(username))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("lines", List.of(lines)))));
    }

    private long request(long storeId, Map<Long, Integer> quantities) {
        long id = jdbc.queryForObject("INSERT INTO replenishment_request (requesting_store_id, created_by) VALUES (?, ?) "
                + "RETURNING id", Long.class, storeId, branchManager.getId());
        quantities.forEach((productId, quantity) -> jdbc.update("INSERT INTO replenishment_request_line "
                + "(replenishment_request_id, product_id, requested_qty) VALUES (?, ?, ?)", id, productId, quantity));
        return id;
    }

    private long lastOrder() {
        return jdbc.queryForObject("SELECT max(id) FROM transfer_order", Long.class);
    }

    private List<Long> recipients(String type) {
        return jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = ?", Long.class, type);
    }

    private void stock(long storeId, long productId, int available) {
        jdbc.update("INSERT INTO stock_balance (store_id, product_id, available_qty) VALUES (?, ?, ?)", storeId,
                productId, available);
    }

    private long product(String sku, String name, long category) {
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?) RETURNING id", Long.class, sku, name, category, owner.getId());
    }

    private static Map<String, Object> line(long productId, int plannedQty) {
        return Map.of("productId", productId, "plannedQty", plannedQty);
    }

    private static Map<String, Object> makeup(long productId, int plannedQty, long caseId) {
        return Map.of("productId", productId, "plannedQty", plannedQty, "makeupForCaseId", caseId);
    }

    private static Map<String, Object> buy(long productId, int toPurchaseQty) {
        return Map.of("productId", productId, "toPurchaseQty", toPurchaseQty);
    }
}
