package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two warehouses with their users, three suppliers and five parts, shared by the purchase order API tests. The clock
 * starts on 13/10/2026 at 08:00 Vietnam time.
 */
abstract class PurchaseOrderTestData extends ApiTestUsers {

    protected static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    protected long giaLam;
    protected long longBien;
    /** A warehouse that has not opened yet. */
    protected long planned;

    protected UserAccount owner;
    /** Branch Manager of Gia Lâm. */
    protected UserAccount manager;
    /** Branch Manager of Long Biên. */
    protected UserAccount otherManager;
    /** Staff of Gia Lâm. */
    protected UserAccount staff;
    protected UserAccount staff2;
    protected String ownerToken;
    protected String managerToken;
    protected String otherManagerToken;
    protected String staffToken;
    protected String staff2Token;
    protected String adminToken;

    /** "Honda Việt Nam" (NCC-HD), "Yamaha Motor" (NCC-YM), and "Phụ tùng Cũ" (NCC-CU), which no longer trades. */
    protected long honda;
    protected long yamaha;
    protected long inactiveSupplier;

    /** MA-PHANH-01 "Má phanh trước". */
    protected long pad;
    /** NHOT-01 "Nhớt 10W40". */
    protected long oil;
    /** DEN-01 "Đèn pha". */
    protected long lamp;
    /** LOC-GIO-01 "Lọc gió". */
    protected long filter;
    /** CU-01 "Gương cũ", discontinued. */
    protected long discontinued;

    @BeforeEach
    void createPurchaseOrderData() throws Exception {
        resetDatabaseAndClock();
        giaLam = createStore("Gia Lâm");
        longBien = createStore("Long Biên");
        planned = id("INSERT INTO store (store_name, address) VALUES ('Cầu Giấy', 'Hà Nội') RETURNING id");
        owner = createUser("owner", Role.OWNER, null);
        manager = createUser("bm", Role.BRANCH_MANAGER, giaLam);
        otherManager = createUser("bm2", Role.BRANCH_MANAGER, longBien);
        staff = createUser("staff", Role.STAFF, giaLam);
        staff2 = createUser("staff2", Role.STAFF, giaLam);
        createUser("admin", Role.ADMIN, null);
        signInAll();

        honda = id("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC-HD', 'Honda Việt Nam') RETURNING id");
        yamaha = id("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC-YM', 'Yamaha Motor') RETURNING id");
        inactiveSupplier = id("INSERT INTO supplier (supplier_code, supplier_name, status) "
                + "VALUES ('NCC-CU', 'Phụ tùng Cũ', 'INACTIVE') RETURNING id");

        long category = id("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Phụ tùng', 'MOTORCYCLE') "
                + "RETURNING id");
        pad = part("MA-PHANH-01", "Má phanh trước", category);
        oil = part("NHOT-01", "Nhớt 10W40", category);
        lamp = part("DEN-01", "Đèn pha", category);
        filter = part("LOC-GIO-01", "Lọc gió", category);
        discontinued = part("CU-01", "Gương cũ", category);
        discontinue(discontinued);
    }

    private void signInAll() throws Exception {
        ownerToken = signIn("owner");
        managerToken = signIn("bm");
        otherManagerToken = signIn("bm2");
        staffToken = signIn("staff");
        staff2Token = signIn("staff2");
        adminToken = signIn("admin");
    }

    /** Moves the clock to 08:00 Vietnam time of {@code day} and signs everyone in again, as access tokens expire. */
    protected void today(LocalDate day) throws Exception {
        clock.set(Instant.parse(day + "T01:00:00Z"));
        signInAll();
    }

    protected long part(String sku, String name, long category) {
        return id("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) VALUES (?, ?, ?, 'cái', ?) "
                + "RETURNING id", sku, name, category, owner.getId());
    }

    protected void discontinue(long product) {
        jdbc.update("UPDATE product SET status = 'DISCONTINUED', discontinued_at = now() WHERE id = ?", product);
    }

    protected static Map<String, Object> line(long product, Object quantity, Object purchasePrice) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", product);
        line.put("quantity", quantity);
        line.put("purchasePrice", purchasePrice);
        return line;
    }

    @SafeVarargs
    protected static Map<String, Object> order(long supplier, long store, LocalDate expectedArrival, String note,
                                               Map<String, Object>... lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplierId", supplier);
        body.put("storeId", store);
        body.put("expectedArrival", expectedArrival == null ? null : expectedArrival.toString());
        body.put("note", note);
        body.put("lines", Arrays.asList(lines));
        return body;
    }

    @SafeVarargs
    protected static Map<String, Object> change(LocalDate expectedArrival, String note, String reason,
                                                Map<String, Object>... lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedArrival", expectedArrival == null ? null : expectedArrival.toString());
        body.put("note", note);
        body.put("lines", Arrays.asList(lines));
        body.put("reason", reason);
        return body;
    }

    protected ResultActions create(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/purchase-orders").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    /** Owner creates the PO; its number. */
    protected long created(Map<String, Object> body) throws Exception {
        String response = create(ownerToken, body).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    protected ResultActions update(String token, long order, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/purchase-orders/" + order).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions cancel(String token, long order, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        return mvc.perform(post("/api/v1/purchase-orders/" + order + "/cancel").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions detail(String token, long order) throws Exception {
        return mvc.perform(get("/api/v1/purchase-orders/" + order).header(HttpHeaders.AUTHORIZATION, token));
    }

    /** The PO list with query parameters given as name, value, name, value... */
    protected ResultActions list(String token, String... parameters) throws Exception {
        var request = get("/api/v1/purchase-orders").header(HttpHeaders.AUTHORIZATION, token);
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        return mvc.perform(request);
    }

    protected ResultActions assignedToMe(String token) throws Exception {
        return mvc.perform(get("/api/v1/goods-receipt-notes/assigned-to-me").header(HttpHeaders.AUTHORIZATION, token));
    }

    /** [type, message, refType, refId] of the user's notifications, oldest first. */
    protected List<List<Object>> notificationsOf(UserAccount user) {
        return jdbc.query("SELECT notification_type, message, ref_type, ref_id FROM notification WHERE recipient_id = ? "
                        + "ORDER BY id", (rs, i) -> List.<Object>of(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4)), user.getId());
    }

    /**
     * A delivery of the PO. Unless it is still {@code AWAITING_INSPECTION}, {@code recordedBy} has recorded the result;
     * {@code confirmedBy} is null until it is CONFIRMED.
     */
    protected long delivery(long order, String documentNo, LocalDateTime arrivedAt, String status, UserAccount recordedBy,
                            UserAccount confirmedBy) {
        return id("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, delivery_document_no, "
                        + "delivery_document_date, arrived_at, status, recorded_by, submitted_at, confirmed_by, confirmed_at) "
                        + "VALUES ('SUPPLIER_DELIVERY', ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id", order, documentNo,
                arrivedAt.toLocalDate(), arrivedAt, status, recordedBy == null ? null : recordedBy.getId(),
                recordedBy == null ? null : arrivedAt.plusHours(1), confirmedBy == null ? null : confirmedBy.getId(),
                confirmedBy == null ? null : arrivedAt.plusHours(2));
    }

    /** A receipt of goods sent from another warehouse, which is no supplier delivery. */
    protected long transferIn(long from, long to, LocalDateTime arrivedAt) {
        long transfer = id("INSERT INTO transfer_order (source_store_id, dest_store_id, status, created_by) "
                + "VALUES (?, ?, 'IN_TRANSIT', ?) RETURNING id", from, to, owner.getId());
        return id("INSERT INTO goods_receipt_note (receipt_type, transfer_order_id, arrived_at) "
                + "VALUES ('TRANSFER_IN', ?, ?) RETURNING id", transfer, arrivedAt);
    }

    /** The result of one part in a delivery: delivered = passed + wrong + damaged. */
    protected void result(long receipt, long product, int documentQty, int passed, int wrongItem, int damaged,
                          int excess, String excessDecision) {
        jdbc.update("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, delivered_qty, "
                        + "passed_qty, wrong_item_qty, damaged_qty, excess_qty, excess_decision) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", receipt, product, documentQty, passed + wrongItem + damaged,
                passed, wrongItem, damaged, excess, excessDecision);
    }

    protected void inspection(long receipt, UserAccount assignee, String status, LocalDateTime assignedAt) {
        jdbc.update("INSERT INTO warehouse_task (task_type, goods_receipt_note_id, assigned_to, assigned_by, assigned_at, "
                + "status, result_submitted_at) VALUES ('INSPECTION', ?, ?, ?, ?, ?, ?)", receipt, assignee.getId(),
                manager.getId(), assignedAt, status, status.equals("ASSIGNED") ? null : assignedAt.plusMinutes(30));
    }

    protected long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
