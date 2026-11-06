package fu.hl.g64.motorflow.sales;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Two stores, their people, four parts with retail prices (one without, one discontinued), lots with and without
 * expiry dates, a 10% discount threshold and a 5% minimum margin. The clock starts on 13/10/2026 at 08:00 Vietnam time.
 */
public abstract class SalesOrderTestData extends ApiTestUsers {

    protected static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Autowired
    protected InventoryPostingService posting;
    @Autowired
    protected TransactionTemplate tx;

    protected long store;
    protected long otherStore;
    protected UserAccount owner;
    protected UserAccount manager;
    protected UserAccount otherManager;
    protected UserAccount staff;
    protected UserAccount otherStaff;
    protected String ownerToken;
    protected String managerToken;
    protected String otherManagerToken;
    protected String staffToken;
    protected String otherStaffToken;

    /** 100,000 đ; 3 units expiring 01/12, 5 without expiry, 2 expired on 01/10; average cost 60,000. Barcode 8930001. */
    protected long pad;
    /** 85,000 đ; 4 units at 70,000. */
    protected long oil;
    /** No retail price. */
    protected long lamp;
    /** Discontinued, 50,000 đ. */
    protected long old;
    protected long customer;
    protected long padSoon;
    protected long padNoExpiry;

    private long supplier;
    private long purchaseOrder;
    private int documents;

    @BeforeEach
    protected void createSalesData() throws Exception {
        resetDatabaseAndClock();
        store = createStore("Gia Lâm");
        otherStore = createStore("Long Biên");
        owner = createUser("owner", Role.OWNER, null);
        manager = createUser("bm", Role.BRANCH_MANAGER, store);
        otherManager = createUser("bm2", Role.BRANCH_MANAGER, otherStore);
        staff = createUser("staff", Role.STAFF, store);
        otherStaff = createUser("staff2", Role.STAFF, store);
        signInAll();

        long category = id("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Phanh', 'MOTORCYCLE') "
                + "RETURNING id");
        pad = part("MA-PHANH-01", "Má phanh trước", category);
        oil = part("NHOT-01", "Nhớt 10W40", category);
        lamp = part("DEN-01", "Đèn pha", category);
        old = part("CU-01", "Phụ tùng cũ", category);
        jdbc.update("UPDATE product SET status = 'DISCONTINUED', discontinued_at = now() WHERE id = ?", old);
        jdbc.update("INSERT INTO product_identifier (product_id, identifier_type, identifier_value) "
                + "VALUES (?, 'BARCODE', '8930001')", pad);
        price(pad, 100_000, TODAY.minusDays(12).atStartOfDay());
        price(oil, 85_000, TODAY.minusDays(12).atStartOfDay());
        price(old, 50_000, TODAY.minusDays(12).atStartOfDay());
        limit("DISCOUNT_THRESHOLD", "10");
        limit("MINIMUM_MARGIN", "5");

        supplier = id("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC001', 'Honda') RETURNING id");
        purchaseOrder = id("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?) "
                + "RETURNING id", supplier, store, owner.getId());
        padSoon = lot(pad, 3, 60_000, LocalDate.of(2026, 12, 1));
        padNoExpiry = lot(pad, 5, 60_000, null);
        lot(pad, 2, 60_000, LocalDate.of(2026, 10, 1));
        lot(oil, 4, 70_000, null);
        jdbc.update("UPDATE stock_balance SET storage_area = 'Kệ A', bin_location = 'A-01' WHERE store_id = ? "
                + "AND product_id = ?", store, pad);
        customer = id("INSERT INTO customer (customer_name, phone, address, created_by) "
                + "VALUES ('Anh Tuấn', '0912345678', 'Gia Lâm', ?) RETURNING id", owner.getId());
    }

    protected void signInAll() throws Exception {
        ownerToken = signIn("owner");
        managerToken = signIn("bm");
        otherManagerToken = signIn("bm2");
        staffToken = signIn("staff");
        otherStaffToken = signIn("staff2");
    }

    protected long part(String sku, String name, long category) {
        return id("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) VALUES (?, ?, ?, 'cái', ?) "
                + "RETURNING id", sku, name, category, owner.getId());
    }

    protected void price(long product, long unitPrice, java.time.LocalDateTime from) {
        jdbc.update("INSERT INTO pricing_policy (policy_type, product_id, unit_price, effective_from, status, set_by, "
                + "set_at) VALUES ('RETAIL_PRICE', ?, ?, ?, 'ACTIVE', ?, ?)", product, unitPrice, from, owner.getId(),
                from);
    }

    protected void limit(String type, String rate) {
        jdbc.update("INSERT INTO pricing_policy (policy_type, rate, effective_from, status, set_by, set_at) "
                + "VALUES (?, ?::numeric, now() - interval '30 days', 'ACTIVE', ?, now())", type, rate, owner.getId());
    }

    /** A lot received from the supplier into the store's available stock. */
    protected long lot(long product, int quantity, long unitPrice, LocalDate expiry) {
        long grn = id("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, delivery_document_no, "
                + "arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, ?, now()) RETURNING id", purchaseOrder,
                "PX-" + ++documents);
        long line = id("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, "
                + "delivered_qty, passed_qty) VALUES (?, ?, ?, ?, ?) RETURNING id", grn, product, quantity, quantity,
                quantity);
        return tx.execute(status -> posting.receiveNewLot(new NewLotPosting(store, product, LotSourceType.RECEIPT,
                line, supplier, TODAY.minusDays(20 - documents), unitPrice, "L" + documents, null, expiry,
                Map.of(StockStatus.AVAILABLE, quantity), MovementType.RECEIPT_CONFIRMED,
                SourceRef.goodsReceiptNote(grn), owner.getId(), null))).getId();
    }

    protected long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ---- requests ------------------------------------------------------------------------------------------------

    /** [productId, quantity, productId, quantity, ...] */
    protected static List<Map<String, Object>> lines(long... productAndQuantity) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            lines.add(Map.of("productId", productAndQuantity[i], "quantity", productAndQuantity[i + 1]));
        }
        return lines;
    }

    protected static Map<String, Object> order(Long storeId, Long customerId, String priceType, String rate,
                                               List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeId", storeId);
        body.put("customerId", customerId);
        body.put("priceType", priceType);
        body.put("discountRate", rate);
        body.put("lines", lines);
        return body;
    }

    protected ResultActions create(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/sales-orders").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected long createOrder(long... productAndQuantity) throws Exception {
        String body = create(managerToken, order(null, customer, "RETAIL", null, lines(productAndQuantity)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    protected ResultActions update(String token, long order, int version, Map<String, Object> body)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(body);
        request.remove("storeId");
        request.put("version", version);
        return mvc.perform(put("/api/v1/sales-orders/" + order).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(request)));
    }

    protected ResultActions detail(String token, long order) throws Exception {
        return mvc.perform(get("/api/v1/sales-orders/" + order).header(HttpHeaders.AUTHORIZATION, token));
    }

    protected ResultActions list(String token, String... parameters) throws Exception {
        var request = get("/api/v1/sales-orders").header(HttpHeaders.AUTHORIZATION, token);
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        return mvc.perform(request);
    }

    protected ResultActions assign(String token, String type, long document, UserAccount assignee) throws Exception {
        return mvc.perform(post("/api/v1/warehouse-tasks").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("taskType", type, "documentId", document,
                        "assigneeId", assignee.getId()))));
    }

    /** [productId, quantity, reason, productId, quantity, reason, ...]; a null reason is left out. */
    protected ResultActions pick(String token, long order, int version, Object... lines) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 3) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", lines[i]);
            row.put("pickedQty", lines[i + 1]);
            row.put("shortReason", lines[i + 2]);
            rows.add(row);
        }
        return mvc.perform(put("/api/v1/sales-orders/" + order + "/picked-lines").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", version, "lines", rows))));
    }

    /** [productId, deliveredQty, ...] */
    protected ResultActions confirm(String token, long order, int version, long... lines) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) {
            rows.add(Map.of("productId", lines[i], "deliveredQty", lines[i + 1]));
        }
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/confirm").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", version, "customerAgreed", true, "lines", rows))));
    }

    protected int notifications(UserAccount recipient, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE recipient_id = ? AND notification_type = ?",
                Integer.class, recipient.getId(), type);
    }

    protected String taskStatus(String type, long document) {
        return jdbc.queryForObject("SELECT status FROM warehouse_task WHERE task_type = ? AND (sales_order_id = ? "
                + "OR transfer_order_id = ?) ORDER BY id DESC LIMIT 1", String.class, type, document, document);
    }
}
