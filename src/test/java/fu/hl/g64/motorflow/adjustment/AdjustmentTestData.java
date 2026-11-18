package fu.hl.g64.motorflow.adjustment;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gia Lâm holds two lots of oil (5 at 80 000 and 7 at 90 000, moving average 85 000) and three lots of air filters
 * (4 expiring 31/01/2027, 3 expiring 30/06/2027 and 2 without expiry; average 50 000). Long Biên has its own manager.
 */
abstract class AdjustmentTestData extends ApiTestUsers {

    protected static final String REQUESTS = "/api/v1/adjustment-requests";

    protected long store;
    protected long otherStore;
    protected UserAccount owner;
    protected UserAccount manager;
    protected UserAccount manager2;
    protected UserAccount otherManager;
    protected String ownerToken;
    protected String managerToken;
    protected String manager2Token;
    protected String otherManagerToken;
    protected String staffToken;

    protected long category;
    protected long oil;
    protected long filter;
    protected long oilLot1;
    protected long oilLot2;
    protected long filterLotA;
    protected long filterLotB;
    protected long filterLotPlain;
    private long openingCount;

    @BeforeEach
    void createAdjustmentData() throws Exception {
        resetDatabaseAndClock();
        store = createStore("Gia Lâm");
        otherStore = createStore("Long Biên");
        owner = createUser("owner", Role.OWNER, null);
        manager = createUser("bm", Role.BRANCH_MANAGER, store);
        manager2 = createUser("bm2", Role.BRANCH_MANAGER, store);
        otherManager = createUser("bm.lb", Role.BRANCH_MANAGER, otherStore);
        createUser("staff", Role.STAFF, store);
        signInAll();

        category = id("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Phụ tùng', 'MOTORCYCLE') "
                + "RETURNING id");
        oil = part("NHOT-01", "Nhớt 10W40", 85_000);
        filter = part("LOC-01", "Lọc gió", 50_000);
        openingCount = id("INSERT INTO stock_count (store_id, count_type, count_date, scope_type, status, created_by) "
                + "VALUES (?, 'OPENING', '2026-09-30', 'WHOLE', 'COMPLETED', ?) RETURNING id", store, owner.getId());
        oilLot1 = lot(store, oil, LocalDate.of(2026, 10, 1), null, 5, 80_000);
        oilLot2 = lot(store, oil, LocalDate.of(2026, 10, 5), null, 7, 90_000);
        filterLotA = lot(store, filter, LocalDate.of(2026, 10, 3), LocalDate.of(2027, 1, 31), 4, 50_000);
        filterLotB = lot(store, filter, LocalDate.of(2026, 10, 4), LocalDate.of(2027, 6, 30), 3, 50_000);
        filterLotPlain = lot(store, filter, LocalDate.of(2026, 10, 2), null, 2, 50_000);
    }

    protected void signInAll() throws Exception {
        ownerToken = signIn("owner");
        managerToken = signIn("bm");
        manager2Token = signIn("bm2");
        otherManagerToken = signIn("bm.lb");
        staffToken = signIn("staff");
    }

    protected long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    protected long part(String sku, String name, Integer movingAvgCost) {
        return id("INSERT INTO product (sku, part_name, category_id, base_unit, moving_avg_cost, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?, ?) RETURNING id", sku, name, category, movingAvgCost, owner.getId());
    }

    /** A lot holding {@code qty} AVAILABLE in the warehouse, with the stock balance raised to match. */
    protected long lot(long storeId, long product, LocalDate received, LocalDate expiry, int qty, long price) {
        long lot = id("INSERT INTO lot (product_id, source_type, stock_count_id, store_id, received_date, "
                        + "purchase_unit_price, expiry_date) VALUES (?, 'OPENING', ?, ?, ?, ?, ?) RETURNING id",
                product, openingCount, storeId, received, price, expiry);
        jdbc.update("INSERT INTO lot_balance (lot_id, store_id, stock_status, quantity) VALUES (?, ?, 'AVAILABLE', ?)",
                lot, storeId, qty);
        jdbc.update("INSERT INTO stock_balance (store_id, product_id, available_qty) VALUES (?, ?, ?) "
                + "ON CONFLICT (store_id, product_id) DO UPDATE SET available_qty = stock_balance.available_qty "
                + "+ excluded.available_qty", storeId, product, qty);
        return lot;
    }

    /** Moves {@code qty} of the lot from AVAILABLE to {@code status} in the balances, as a sale or count would. */
    protected void takeFromLot(long lotId, int qty) {
        jdbc.update("UPDATE lot_balance SET quantity = quantity - ? WHERE lot_id = ? AND stock_status = 'AVAILABLE'",
                qty, lotId);
        jdbc.update("UPDATE stock_balance b SET available_qty = available_qty - ? FROM lot l "
                + "WHERE l.id = ? AND b.store_id = l.store_id AND b.product_id = l.product_id", qty, lotId);
    }

    protected int available(long product) {
        return jdbc.queryForObject("SELECT available_qty FROM stock_balance WHERE store_id = ? AND product_id = ?",
                Integer.class, store, product);
    }

    protected int lotQty(long lot) {
        return jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM lot_balance WHERE lot_id = ? "
                + "AND stock_status = 'AVAILABLE'", Integer.class, lot);
    }

    protected long notifications(UserAccount user, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE recipient_id = ? AND notification_type = ?",
                Long.class, user.getId(), type);
    }

    // ---------------------------------------------------------------------------------------------- bodies

    /** {productId, lotId or null, status, quantity} per line. */
    protected static Map<String, Object> line(long product, Long lot, String status, int quantity) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", product);
        line.put("lotId", lot);
        line.put("stockStatus", status);
        line.put("quantity", quantity);
        return line;
    }

    @SafeVarargs
    protected static Map<String, Object> loss(Long storeId, Map<String, Object>... lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeId", storeId);
        body.put("reason", "Thùng nhớt bị đổ vỡ khi sắp xếp kệ");
        body.put("lines", List.of(lines));
        body.put("attachments", new ArrayList<>(List.of(photo())));
        return body;
    }

    protected static Map<String, Object> photo() {
        return Map.of("storageKey", "motoflow/attachments/" + UUID.randomUUID(), "fileName", "anh-hong.jpg",
                "contentType", "image/jpeg", "fileSize", 120_000);
    }

    // ---------------------------------------------------------------------------------------------- calls

    protected ResultActions call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        return mvc.perform(request);
    }

    protected ResultActions preview(String token, Map<String, Object> body) throws Exception {
        return call(post(REQUESTS + "/stock-loss/preview"), token, body);
    }

    protected ResultActions submit(String token, Map<String, Object> body) throws Exception {
        return call(post(REQUESTS + "/stock-loss"), token, body);
    }

    protected long submitted(String token, Map<String, Object> body) throws Exception {
        String response = submit(token, body).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    protected ResultActions detail(String token, long id) throws Exception {
        return call(get(REQUESTS + "/" + id), token, null);
    }

    protected ResultActions list(String token, String... parameters) throws Exception {
        MockHttpServletRequestBuilder request = get(REQUESTS);
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        return call(request, token, null);
    }

    protected ResultActions act(String token, long id, String action) throws Exception {
        return call(post(REQUESTS + "/" + id + "/" + action), token, null);
    }

    protected ResultActions reject(String token, long id, String reason) throws Exception {
        return call(post(REQUESTS + "/" + id + "/reject"), token, reason == null ? Map.of() : Map.of("reason", reason));
    }
}
