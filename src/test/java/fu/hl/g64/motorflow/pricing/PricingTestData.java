package fu.hl.g64.motorflow.pricing;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Users, a category tree, three parts and their supplier receipts, shared by the pricing API tests. The clock starts
 * on 13/10/2026 at 08:00 Vietnam time.
 */
abstract class PricingTestData extends ApiTestUsers {

    protected static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    protected UserAccount owner;
    /** Branch Manager without retail price permission. */
    protected UserAccount manager;
    /** Branch Manager holding retail price permission. */
    protected UserAccount pricer;
    protected String ownerToken;
    protected String managerToken;
    protected String pricerToken;
    protected String staffToken;
    protected String adminToken;

    /** "Phanh" with the sub-category "Má phanh"; "Dầu nhớt"; "Đèn". */
    protected long brakes;
    protected long brakePads;
    protected long oils;
    protected long lights;
    /** In "Má phanh": bought at 100,000 on 01/10 and at 123,456 on 10/10; an opening lot on 12/10 is no purchase. */
    protected long pad;
    /** In "Dầu nhớt": bought at 85,000 on 05/10. */
    protected long oil;
    /** In "Đèn": never bought. */
    protected long lamp;

    private long store;
    private long supplier;
    private long purchaseOrder;
    private int documents;

    @BeforeEach
    void createPricingData() throws Exception {
        resetDatabaseAndClock();
        store = createStore("Gia Lâm");
        owner = createUser("owner", Role.OWNER, null);
        manager = createUser("bm", Role.BRANCH_MANAGER, store);
        pricer = createUser("bm2", Role.BRANCH_MANAGER, createStore("Long Biên"));
        pricer.setPermission(PermissionCode.RETAIL_PRICE_EDIT, true, LocalDateTime.now(clock));
        users.save(pricer);
        createUser("staff", Role.STAFF, store);
        createUser("admin", Role.ADMIN, null);
        signInAll();

        brakes = id("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Phanh', 'MOTORCYCLE') RETURNING id");
        brakePads = id("INSERT INTO part_category (category_name, vehicle_type, parent_id) "
                + "VALUES ('Má phanh', 'MOTORCYCLE', ?) RETURNING id", brakes);
        oils = id("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Dầu nhớt', 'MOTORCYCLE') RETURNING id");
        lights = id("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Đèn', 'MOTORCYCLE') RETURNING id");
        pad = part("MA-PHANH-01", "Má phanh trước", brakePads);
        oil = part("NHOT-01", "Nhớt 10W40", oils);
        lamp = part("DEN-01", "Đèn pha", lights);

        supplier = id("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC001', 'Honda') RETURNING id");
        purchaseOrder = id("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?) RETURNING id",
                supplier, store, owner.getId());
        received(pad, 100_000, LocalDate.of(2026, 10, 1));
        received(pad, 123_456, LocalDate.of(2026, 10, 10));
        received(oil, 85_000, LocalDate.of(2026, 10, 5));
        opening(pad, 50_000, LocalDate.of(2026, 10, 12));
    }

    private void signInAll() throws Exception {
        ownerToken = signIn("owner");
        managerToken = signIn("bm");
        pricerToken = signIn("bm2");
        staffToken = signIn("staff");
        adminToken = signIn("admin");
    }

    protected long part(String sku, String name, long category) {
        return id("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) VALUES (?, ?, ?, 'cái', ?) "
                + "RETURNING id", sku, name, category, owner.getId());
    }

    /** A lot from a supplier receipt: a purchase price that suggestions use (BR-69). */
    protected void received(long product, long price, LocalDate date) {
        long grn = id("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, delivery_document_no, arrived_at) "
                + "VALUES ('SUPPLIER_DELIVERY', ?, ?, now()) RETURNING id", purchaseOrder, "PX-" + ++documents);
        long line = id("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, "
                + "delivered_qty, passed_qty) VALUES (?, ?, 5, 5, 5) RETURNING id", grn, product);
        jdbc.update("INSERT INTO lot (product_id, source_type, goods_receipt_note_line_id, supplier_id, store_id, "
                + "received_date, purchase_unit_price) VALUES (?, 'RECEIPT', ?, ?, ?, ?, ?)",
                product, line, supplier, store, date, price);
    }

    /** A lot from the opening count, which is no purchase. */
    private void opening(long product, long price, LocalDate date) {
        long count = id("INSERT INTO stock_count (store_id, count_type, count_date, scope_type, created_by) "
                + "VALUES (?, 'OPENING', ?, 'WHOLE', ?) RETURNING id", store, date, owner.getId());
        jdbc.update("INSERT INTO lot (product_id, source_type, stock_count_id, store_id, received_date, "
                + "purchase_unit_price) VALUES (?, 'OPENING', ?, ?, ?, ?)", product, count, store, date, price);
    }

    /** Moves the clock to 08:00 Vietnam time of {@code day} and signs everyone in again, as access tokens expire. */
    protected void today(LocalDate day) throws Exception {
        clock.set(Instant.parse(day + "T01:00:00Z"));
        signInAll();
    }

    protected ResultActions setPrice(String token, long product, long price, LocalDate from, String reason,
                                     boolean fromSuggestion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", product);
        body.put("unitPrice", price);
        body.put("effectiveDate", from.toString());
        body.put("reason", reason);
        body.put("fromSuggestion", fromSuggestion);
        return mvc.perform(post("/api/v1/pricing/retail-prices").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions priceList(String token, String query) throws Exception {
        return mvc.perform(get("/api/v1/pricing/retail-prices" + query).header(HttpHeaders.AUTHORIZATION, token));
    }

    protected ResultActions priceHistory(String token, long product) throws Exception {
        return mvc.perform(get("/api/v1/pricing/retail-prices/" + product + "/history")
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    protected ResultActions setLimit(String token, String type, Object rate, LocalDate from) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limitType", type);
        body.put("rate", rate);
        body.put("effectiveDate", from.toString());
        return mvc.perform(put("/api/v1/pricing/sales-limits").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    /** Saves a markup configuration: {@code categoryRates} and {@code productRates} map ids to rates. */
    protected ResultActions setMarkup(String token, LocalDate from, String rounding, Map<Long, Object> categoryRates,
                                      Map<Long, Object> productRates) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("effectiveDate", from.toString());
        body.put("roundingRule", rounding);
        body.put("categories", rates("categoryId", categoryRates));
        body.put("products", rates("productId", productRates));
        return mvc.perform(post("/api/v1/pricing/markup-rules").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected static List<Map<String, Object>> rates(String key, Map<Long, Object> rates) {
        return rates.entrySet().stream().map(e -> Map.<String, Object>of(key, e.getKey(), "markupRate", e.getValue()))
                .toList();
    }

    protected long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
