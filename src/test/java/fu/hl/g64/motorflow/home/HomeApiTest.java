package fu.hl.g64.motorflow.home;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HomeApiTest extends IntegrationTest {

    private static final String PASSWORD = "Owner2026pass";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JsonMapper jsonMapper;

    private UserAccount owner;
    /** CH01 holds the central warehouse; CH02 and CH03 are branches. */
    private long central;
    private long branch;
    private long otherBranch;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        owner = createUser("owner", Role.OWNER, null);
        central = createStore("CH01", true, "ACTIVE");
        branch = createStore("CH02", false, "ACTIVE");
        otherBranch = createStore("CH03", false, "PLANNED");
    }

    @Test
    void adminGetsAdministrationShortcutsAndNoNotifications() throws Exception {
        createUser("admin01", Role.ADMIN, null);
        UserAccount locked = createUser("staff01", Role.STAFF, branch);
        jdbc.update("UPDATE user_account SET status = 'LOCKED' WHERE id = ?", locked.getId());
        newAccount("staff02", Role.STAFF, branch);
        UserAccount lockedNew = newAccount("staff03", Role.STAFF, branch);
        jdbc.update("UPDATE user_account SET status = 'LOCKED' WHERE id = ?", lockedNew.getId());

        home(signIn("admin01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.storeId").doesNotExist())
                .andExpect(jsonPath("$.notifications").doesNotExist())
                .andExpect(jsonPath("$.shortcuts[*].key",
                        contains("LOCKED_ACCOUNTS", "TEMPORARY_PASSWORD_ACCOUNTS", "PLANNED_STORES")))
                .andExpect(jsonPath("$.shortcuts[*].count", contains(2, 1, 1)));
    }

    @Test
    void ownerGetsTheApprovalQueueAndUnreadNotifications() throws Exception {
        long centralWarehouse = warehouseOf(central);
        adjustment(branch, "PENDING");
        adjustment(otherBranch, "PENDING");
        adjustment(branch, "APPROVED");
        long product = addProduct("BRK-01");
        long otherProduct = addProduct("CHN-01");
        long receipt = supplierDelivery(centralWarehouse, "AWAITING_CONFIRMATION");
        receiptLine(receipt, product, "AWAITING_OWNER");
        receiptLine(receipt, otherProduct, "AWAITING_OWNER");
        receiptLine(supplierDelivery(centralWarehouse, "CONFIRMED"), product, "ACCEPTED");
        jdbc.update("INSERT INTO stock_count (warehouse_id, count_type, scope_type, status, created_by) "
                + "VALUES (?, 'OPENING', 'WHOLE', 'AWAITING_APPROVAL', ?)", centralWarehouse, owner.getId());
        replenishment(branch, "AWAITING_COORDINATION");
        replenishment(branch, "IN_PROGRESS");
        for (int i = 1; i <= 7; i++) {
            notify(owner.getId(), "Thông báo " + i, LocalDateTime.of(2026, 10, i, 8, 0), false);
        }
        notify(owner.getId(), "Đã đọc", LocalDateTime.of(2026, 10, 12, 8, 0), true);
        notify(createUser("bm01", Role.BRANCH_MANAGER, central).getId(), "Của người khác",
                LocalDateTime.of(2026, 10, 12, 8, 0), false);

        home(signIn("owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.notifications.unread").value(7))
                .andExpect(jsonPath("$.notifications.latest[*].message",
                        contains("Thông báo 7", "Thông báo 6", "Thông báo 5", "Thông báo 4", "Thông báo 3")))
                .andExpect(jsonPath("$.notifications.latest[0].refType").value("SALES_ORDER"))
                .andExpect(jsonPath("$.shortcuts[*].key", contains("ADJUSTMENTS_AWAITING_APPROVAL",
                        "EXCESS_AWAITING_DECISION", "STOCK_COUNTS_AWAITING_APPROVAL", "REPLENISHMENTS_AWAITING_COORDINATION")))
                .andExpect(jsonPath("$.shortcuts[*].count", contains(2, 1, 1, 1)));
    }

    @Test
    void aBranchManagerGetsTheWorkOfTheirOwnStore() throws Exception {
        UserAccount manager = createUser("bm02", Role.BRANCH_MANAGER, branch);
        UserAccount staff = createUser("staff02", Role.STAFF, branch);
        long branchWarehouse = warehouseOf(branch);
        long centralWarehouse = warehouseOf(central);

        // Orders: one waiting with nobody on it, one already given to Staff, one whose task was cancelled.
        order(branch, "AWAITING_PICKING");
        task("PICKING", "sales_order_id", order(branch, "AWAITING_PICKING"), staff, "ASSIGNED");
        task("PICKING", "sales_order_id", order(branch, "AWAITING_PICKING"), staff, "CANCELLED");
        order(branch, "PICKED");
        long confirmed = order(branch, "CONFIRMED");
        order(otherBranch, "AWAITING_PICKING");
        payment(confirmed, "UNVERIFIED");
        payment(confirmed, "VERIFIED");
        payment(order(otherBranch, "CONFIRMED"), "UNVERIFIED");
        long debt = jdbc.queryForObject("INSERT INTO receivable (origin_type, customer_id, store_id, original_amount, "
                        + "recognized_by, recognized_at) VALUES ('OPENING_BALANCE', ?, ?, 100000, ?, ?) RETURNING id",
                Long.class, customer(), branch, owner.getId(), LocalDateTime.now(clock));
        jdbc.update("INSERT INTO payment (payment_type, receivable_id, amount, payment_method, recorded_by, recorded_at) "
                + "VALUES ('DEBT_PAYMENT', ?, 50000, 'CASH', ?, ?)", debt, owner.getId(), LocalDateTime.now(clock));

        // Receiving: a supplier delivery nobody inspects yet, a transfer already given to Staff, goods sent back
        // to this warehouse awaiting confirmation, and a delivery at another warehouse.
        long delivery = supplierDelivery(branchWarehouse, "AWAITING_INSPECTION");
        long incoming = transfer(centralWarehouse, branchWarehouse, "IN_TRANSIT");
        task("INSPECTION", "goods_receipt_id", transferReceipt("TRANSFER_IN", incoming, "AWAITING_INSPECTION"), staff,
                "ASSIGNED");
        long outgoing = transfer(branchWarehouse, centralWarehouse, "RECONCILING");
        transferReceipt("RETURN_TO_SOURCE", outgoing, "AWAITING_CONFIRMATION");
        transferReceipt("TRANSFER_IN", outgoing, "AWAITING_CONFIRMATION");
        supplierDelivery(centralWarehouse, "AWAITING_CONFIRMATION");
        transfer(branchWarehouse, centralWarehouse, "AWAITING_DISPATCH");
        transfer(centralWarehouse, branchWarehouse, "AWAITING_DISPATCH");
        long product = addProduct("BRK-01");
        jdbc.update("INSERT INTO discrepancy_case (source_type, goods_receipt_id, product_id, short_qty, opened_at) "
                + "VALUES ('SUPPLIER', ?, ?, 2, ?)", delivery, product, LocalDateTime.now(clock));
        replenishment(branch, "AWAITING_COORDINATION");
        notify(manager.getId(), "Có đơn mới", LocalDateTime.now(clock), false);

        home(signIn("bm02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("BRANCH_MANAGER"))
                .andExpect(jsonPath("$.storeId").value(branch))
                .andExpect(jsonPath("$.storeName").value("Cửa hàng CH02"))
                .andExpect(jsonPath("$.notifications.unread").value(1))
                .andExpect(jsonPath("$.shortcuts[*].key", contains("ORDERS_TO_ASSIGN", "ORDERS_AWAITING_CONFIRMATION",
                        "ORDERS_AWAITING_COMPLETION", "PAYMENTS_AWAITING_VERIFICATION", "RECEIPTS_TO_ASSIGN",
                        "RECEIPTS_AWAITING_CONFIRMATION", "TRANSFERS_AWAITING_DISPATCH", "OPEN_DISCREPANCIES")))
                .andExpect(jsonPath("$.shortcuts[*].count", contains(2, 1, 1, 2, 1, 1, 1, 1)));

        createUser("bm01", Role.BRANCH_MANAGER, central);
        home(signIn("bm01"))
                .andExpect(jsonPath("$.shortcuts[0].key").value("REPLENISHMENTS_AWAITING_COORDINATION"))
                .andExpect(jsonPath("$.shortcuts[0].count").value(1))
                .andExpect(jsonPath("$.shortcuts", hasSize(9)))
                .andExpect(jsonPath("$.notifications.unread").value(0))
                .andExpect(jsonPath("$.notifications.latest", hasSize(0)));
    }

    @Test
    void staffGetTheTasksGivenToThemByType() throws Exception {
        UserAccount staff = createUser("staff02", Role.STAFF, branch);
        UserAccount colleague = createUser("staff03", Role.STAFF, branch);
        long branchWarehouse = warehouseOf(branch);
        task("PICKING", "sales_order_id", order(branch, "AWAITING_PICKING"), staff, "ASSIGNED");
        task("PICKING", "sales_order_id", order(branch, "AWAITING_PICKING"), staff, "ASSIGNED");
        task("PICKING", "sales_order_id", order(branch, "PICKED"), staff, "RESULT_SUBMITTED");
        task("PICKING", "sales_order_id", order(branch, "AWAITING_PICKING"), colleague, "ASSIGNED");
        task("INSPECTION", "goods_receipt_id", supplierDelivery(branchWarehouse, "AWAITING_INSPECTION"), staff,
                "ASSIGNED");
        task("DISPATCH_PREPARATION", "transfer_order_id", transfer(branchWarehouse, warehouseOf(central),
                "AWAITING_DISPATCH"), staff, "DONE");

        home(signIn("staff02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.storeName").value("Cửa hàng CH02"))
                .andExpect(jsonPath("$.shortcuts[*].key",
                        contains("PICKING_TASKS", "INSPECTION_TASKS", "DISPATCH_PREPARATION_TASKS", "COUNTING_TASKS")))
                .andExpect(jsonPath("$.shortcuts[*].count", contains(2, 1, 0, 0)));
    }

    @Test
    void aTemporaryPasswordMustBeChangedBeforeTheHomePage() throws Exception {
        newAccount("staff02", Role.STAFF, branch);
        home(signIn("staff02"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG13"));
    }

    private ResultActions home(String token) throws Exception {
        return mvc.perform(get("/api/home").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private long createStore(String code, boolean centralWarehouse, String status) {
        Long storeId = jdbc.queryForObject("INSERT INTO store (store_name, address, status) "
                + "VALUES (?, 'Hà Nội', ?) RETURNING id", Long.class, "Cửa hàng " + code, status);
        jdbc.update("INSERT INTO warehouse (store_id, warehouse_name, is_central) VALUES (?, ?, ?)",
                storeId, "Kho " + code, centralWarehouse);
        return storeId;
    }

    private long warehouseOf(long storeId) {
        return jdbc.queryForObject("SELECT id FROM warehouse WHERE store_id = ?", Long.class, storeId);
    }

    private long addProduct(String sku) {
        Long categoryId = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES (?, 'MOTORCYCLE') RETURNING id", Long.class, "Nhóm " + sku);
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, 'Phụ tùng', ?, 'Cái', ?) RETURNING id", Long.class, sku, categoryId, owner.getId());
    }

    private long customer() {
        return jdbc.queryForObject("INSERT INTO customer (customer_name, created_by) VALUES ('Garage Hùng', ?) "
                + "RETURNING id", Long.class, owner.getId());
    }

    private long order(long storeId, String status) {
        return jdbc.queryForObject("INSERT INTO sales_order (store_id, status, created_by) VALUES (?, ?, ?) RETURNING id",
                Long.class, storeId, status, owner.getId());
    }

    private void payment(long orderId, String status) {
        jdbc.update("INSERT INTO payment (payment_type, sales_order_id, amount, payment_method, status, recorded_by, "
                        + "recorded_at) VALUES ('ORDER_PAYMENT', ?, 10000, 'CASH', ?, ?, ?)", orderId, status, owner.getId(),
                LocalDateTime.now(clock));
    }

    private long supplierDelivery(long warehouseId, String status) {
        Long supplier = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) "
                + "VALUES ('NCC' || nextval('supplier_id_seq'), 'Minh Phát') RETURNING id", Long.class);
        Long request = jdbc.queryForObject("INSERT INTO goods_receipt_request (supplier_id, warehouse_id, status, "
                + "created_by) VALUES (?, ?, 'RECEIVING', ?) RETURNING id", Long.class, supplier, warehouseId, owner.getId());
        return receipt("SUPPLIER_DELIVERY", request, null, status);
    }

    private long transferReceipt(String type, long transferOrderId, String status) {
        return receipt(type, null, transferOrderId, status);
    }

    /** Inspected receipts were recorded by the owner account, which is enough for these counts. */
    private long receipt(String type, Long requestId, Long transferOrderId, String status) {
        boolean inspected = !status.equals("AWAITING_INSPECTION");
        return jdbc.queryForObject("INSERT INTO goods_receipt (receipt_type, goods_receipt_request_id, transfer_order_id, "
                        + "delivery_document_no, arrived_at, status, recorded_by, submitted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class, type, requestId, transferOrderId,
                requestId == null ? null : "PX-" + requestId, LocalDateTime.now(clock), status,
                inspected ? owner.getId() : null, inspected ? LocalDateTime.now(clock) : null);
    }

    private void receiptLine(long receiptId, long productId, String excessDecision) {
        jdbc.update("INSERT INTO goods_receipt_line (goods_receipt_id, product_id, document_qty, delivered_qty, passed_qty, "
                + "excess_qty, excess_decision) VALUES (?, ?, 10, 12, 12, 2, ?)", receiptId, productId, excessDecision);
    }

    private long transfer(long sourceWarehouseId, long destWarehouseId, String status) {
        return jdbc.queryForObject("INSERT INTO transfer_order (source_warehouse_id, dest_warehouse_id, status, created_by) "
                + "VALUES (?, ?, ?, ?) RETURNING id", Long.class, sourceWarehouseId, destWarehouseId, status, owner.getId());
    }

    private void task(String type, String documentColumn, long documentId, UserAccount staff, String status) {
        jdbc.update("INSERT INTO warehouse_task (task_type, " + documentColumn + ", assigned_to, assigned_by, assigned_at, "
                + "status) VALUES (?, ?, ?, ?, ?, ?)", type, documentId, staff.getId(), owner.getId(),
                LocalDateTime.now(clock), status);
    }

    private void adjustment(long storeId, String status) {
        jdbc.update("INSERT INTO adjustment_request (adjustment_type, store_id, reason, status, requested_by) "
                + "VALUES ('STOCK_LOSS', ?, 'Hư hỏng', ?, ?)", storeId, status, owner.getId());
    }

    private void replenishment(long storeId, String status) {
        jdbc.update("INSERT INTO replenishment_request (requesting_store_id, status, created_by) VALUES (?, ?, ?)",
                storeId, status, owner.getId());
    }

    private void notify(long recipientId, String message, LocalDateTime at, boolean read) {
        jdbc.update("INSERT INTO notification (recipient_id, notification_type, message, ref_type, ref_id, is_read, "
                + "created_at) VALUES (?, 'ORDER_CREATED', ?, 'SALES_ORDER', 1, ?, ?)", recipientId, message, read, at);
    }

    /** An account that still has its temporary password. */
    private UserAccount newAccount(String username, Role role, Long store) {
        return users.save(UserAccount.create(username, passwordEncoder.encode(PASSWORD), "Test " + username, role, store,
                null, LocalDateTime.now(clock)));
    }

    private UserAccount createUser(String username, Role role, Long store) {
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = passwordEncoder.encode(PASSWORD);
        UserAccount user = UserAccount.create(username, hash, "Test " + username, role, store, null, now);
        user.changePassword(hash, now);
        return users.save(user);
    }

    private String signIn(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
