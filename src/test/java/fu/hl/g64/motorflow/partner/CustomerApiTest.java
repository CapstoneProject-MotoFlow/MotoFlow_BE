package fu.hl.g64.motorflow.partner;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerApiTest extends IntegrationTest {

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
    private UserAccount branchManager;
    /** CH01 holds the central warehouse; CH02 and CH03 are branches. */
    private long central;
    private long branch;
    private long otherBranch;
    private String ownerToken;
    /** Branch Manager of CH02. */
    private String bmToken;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabaseAndClock();
        owner = createUser("owner", Role.OWNER, null);
        central = createStore("CH01", true);
        branch = createStore("CH02", false);
        otherBranch = createStore("CH03", false);
        branchManager = createUser("bm02", Role.BRANCH_MANAGER, branch);
        ownerToken = signIn("owner");
        bmToken = signIn("bm02");
    }

    @Test
    void aNewCustomerNeedsOnlyANameAndMayNotBuyOnCreditYet() throws Exception {
        String location = send(post("/api/customers"), bmToken, Map.of("customerName", " Garage Hùng "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Garage Hùng"))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.creditStatus").value("NOT_GRANTED"))
                .andExpect(jsonPath("$.totalDebt").value(0))
                .andExpect(jsonPath("$.debtAtOwnStore").value(0))
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith("/api/customers/");
        assertThat(jdbc.queryForMap("SELECT c.created_by, a.actor_id FROM customer c "
                + "JOIN audit_log a ON a.entity_id = c.id AND a.action = 'CUSTOMER_CREATED'"))
                .containsEntry("created_by", branchManager.getId())
                .containsEntry("actor_id", branchManager.getId());

        send(post("/api/customers"), ownerToken, customer("Nguyễn Văn Bình", "0912 345 678"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("0912 345 678"))
                .andExpect(jsonPath("$.taxCode").value("0101234567"))
                .andExpect(jsonPath("$.debtAtOwnStore").doesNotExist());
    }

    @Test
    void invalidFieldsAreListed() throws Exception {
        Map<String, Object> body = customer(" ", "abc");
        body.put("taxCode", "12-34");
        send(post("/api/customers"), bmToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.customerName").value("Vui lòng nhập tên khách hàng."))
                .andExpect(jsonPath("$.fieldErrors.phone").exists())
                .andExpect(jsonPath("$.fieldErrors.taxCode").exists());
    }

    @Test
    void anUpdateChangesTheProfileButNotWhatOrdersRecorded() throws Exception {
        long id = createdId(send(post("/api/customers"), bmToken, customer("Garage Hùng", "0912345678")));
        long order = order(branch, "COMPLETED", "PAID");
        version(order, 1, id, "Garage Hùng");

        Map<String, Object> body = customer("Garage Hùng Thịnh", "");
        body.put("address", "  ");
        send(put("/api/customers/{id}", id), bmToken, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Garage Hùng Thịnh"))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT customer_name_snapshot FROM sales_order_version", String.class))
                .isEqualTo("Garage Hùng");
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity_type = 'CUSTOMER' ORDER BY id", String.class))
                .containsExactly("CUSTOMER_CREATED", "CUSTOMER_UPDATED");

        send(put("/api/customers/{id}", 999999), bmToken, body)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG74"));
        send(get("/api/customers/999999"), bmToken)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG74"));
    }

    @Test
    void theListFindsByNamePhoneOrNumberAndShowsWhatIsOwed() throws Exception {
        long hung = createdId(send(post("/api/customers"), bmToken, customer("Garage Hùng", "0912 345 678")));
        long binh = createdId(send(post("/api/customers"), bmToken, customer("Nguyễn Văn Bình", "0987654321")));
        long tam = createdId(send(post("/api/customers"), bmToken, customer("Cửa hàng Tâm", null)));
        jdbc.update("UPDATE customer SET credit_status = 'ELIGIBLE' WHERE id = ?", hung);

        // At CH02: 1 000 000 + 50 000 corrected - verified 300 000 corrected down to 200 000 = 850 000.
        long credit = receivable(hung, branch, order(branch, "COMPLETED", "CREDIT"), 1_000_000);
        long paid = payment("DEBT_PAYMENT", null, credit, 300_000, "VERIFIED");
        payment("DEBT_PAYMENT", null, credit, 100_000, "UNVERIFIED");
        amountLine(adjustment(branch, "APPLIED", null, credit), null, credit, 50_000);
        amountLine(adjustment(branch, "APPLIED", paid, null), paid, null, -100_000);
        amountLine(adjustment(branch, "PENDING", null, credit), null, credit, 999_000);
        // At CH03: an opening balance; a settled debt at CH02 no longer counts.
        receivable(hung, otherBranch, null, 500_000);
        long settled = receivable(hung, branch, null, 200_000);
        jdbc.update("UPDATE receivable SET settled_at = recognized_at WHERE id = ?", settled);
        // Fully paid but not marked settled yet: nothing is owed.
        long cleared = receivable(binh, branch, null, 100_000);
        payment("DEBT_PAYMENT", null, cleared, 100_000, "VERIFIED");

        send(get("/api/customers"), bmToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].customerName", contains("Cửa hàng Tâm", "Garage Hùng", "Nguyễn Văn Bình")))
                .andExpect(jsonPath("$.items[1].totalDebt").value(1_350_000))
                .andExpect(jsonPath("$.items[1].debtAtOwnStore").value(850_000))
                .andExpect(jsonPath("$.items[1].creditStatus").value("ELIGIBLE"))
                .andExpect(jsonPath("$.items[2].totalDebt").value(0))
                .andExpect(jsonPath("$.items[0].totalDebt").value(0));
        send(get("/api/customers?q=garage"), ownerToken)
                .andExpect(jsonPath("$.items[*].id", contains((int) hung)))
                .andExpect(jsonPath("$.items[0].totalDebt").value(1_350_000))
                .andExpect(jsonPath("$.items[0].debtAtOwnStore").doesNotExist());
        send(get("/api/customers?q=0912345678"), bmToken).andExpect(jsonPath("$.items[*].id", contains((int) hung)));
        send(get("/api/customers?q=NGUYỄN"), bmToken).andExpect(jsonPath("$.items[*].id", contains((int) binh)));
        // A number is a customer number or part of a phone number.
        send(get("/api/customers?q=" + tam), bmToken).andExpect(jsonPath("$.items[*].id", hasItem((int) tam)));
        send(get("/api/customers?q=345 678"), bmToken).andExpect(jsonPath("$.items[*].id", contains((int) hung)));
        send(get("/api/customers?creditStatus=ELIGIBLE"), bmToken)
                .andExpect(jsonPath("$.items[*].id", contains((int) hung)));
        send(get("/api/customers?size=1&page=2"), bmToken)
                .andExpect(jsonPath("$.items[*].id", contains((int) binh)))
                .andExpect(jsonPath("$.totalItems").value(3));
    }

    @Test
    void theDetailShowsOnlyTheDocumentsOfStoresTheViewerSees() throws Exception {
        long hung = createdId(send(post("/api/customers"), bmToken, customer("Garage Hùng", "0912345678")));
        long binh = createdId(send(post("/api/customers"), bmToken, customer("Nguyễn Văn Bình", null)));
        long brake = addProduct("BRK-01", "Má phanh trước");
        long chain = addProduct("CHN-01", "Xích tải");

        long sold = order(branch, "COMPLETED", "CREDIT");
        long soldVersion = version(sold, 1, hung, "Garage Hùng");
        line(soldVersion, 1, brake, 3, 2, 100_000);
        line(soldVersion, 2, chain, 1, null, 50_000);
        long elsewhere = order(otherBranch, "AWAITING_PICKING", null);
        line(version(elsewhere, 1, hung, "Garage Hùng"), 1, brake, 1, null, 120_000);
        long movedToBinh = order(branch, "AWAITING_PICKING", null);
        version(movedToBinh, 1, hung, "Garage Hùng");
        version(movedToBinh, 2, binh, "Nguyễn Văn Bình");

        long debtHere = receivable(hung, branch, sold, 200_000);
        long debtThere = receivable(hung, otherBranch, null, 500_000);
        payment("ORDER_PAYMENT", sold, null, 50_000, "VERIFIED");
        payment("DEBT_PAYMENT", null, debtHere, 80_000, "VERIFIED");
        payment("DEBT_PAYMENT", null, debtThere, 100_000, "UNVERIFIED");
        payment("ORDER_PAYMENT", movedToBinh, null, 10_000, "VERIFIED");
        unmet(sold, chain, 2, "WAITING");
        unmet(elsewhere, chain, 1, "WAITING");
        long closedProduct = addProduct("OIL-01", "Nhớt");
        unmet(sold, closedProduct, 1, "CLOSED");
        creditEvent(hung, "GRANTED", "Khách quen", "2026-10-01 08:00");
        creditEvent(hung, "REVOKED", "Trả chậm", "2026-10-05 08:00");

        send(get("/api/customers/{id}", hung), bmToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.totalDebt").value(620_000))
                .andExpect(jsonPath("$.customer.debtAtOwnStore").value(120_000))
                .andExpect(jsonPath("$.creditHistory[*].eventType", contains("REVOKED", "GRANTED")))
                .andExpect(jsonPath("$.creditHistory[0].decidedBy").value("Test owner"))
                .andExpect(jsonPath("$.debts[*].id", contains((int) debtHere)))
                .andExpect(jsonPath("$.debts[0].remainingAmount").value(120_000))
                .andExpect(jsonPath("$.debts[0].storeName").value("Cửa hàng CH02"))
                .andExpect(jsonPath("$.orders[*].id", contains((int) sold)))
                .andExpect(jsonPath("$.orders[0].total").value(250_000))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.payments", hasSize(2)))
                .andExpect(jsonPath("$.paymentCount").value(2))
                .andExpect(jsonPath("$.unmetDemands[*].sku", contains("CHN-01")))
                .andExpect(jsonPath("$.unmetDemands[0].unmetQty").value(2));

        createUser("bm01", Role.BRANCH_MANAGER, central);
        for (String token : new String[] {ownerToken, signIn("bm01")}) {
            send(get("/api/customers/{id}", hung), token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.debts[*].id", contains((int) debtHere, (int) debtThere)))
                    .andExpect(jsonPath("$.orders[*].id", contains((int) elsewhere, (int) sold)))
                    .andExpect(jsonPath("$.orders[0].total").value(120_000))
                    .andExpect(jsonPath("$.orders[0].storeName").value("Cửa hàng CH03"))
                    .andExpect(jsonPath("$.paymentCount").value(3))
                    .andExpect(jsonPath("$.unmetDemands", hasSize(2)));
        }
        send(get("/api/customers/{id}", binh), bmToken)
                .andExpect(jsonPath("$.orders[*].id", contains((int) movedToBinh)))
                .andExpect(jsonPath("$.payments[*].amount", containsInAnyOrder(10_000)))
                .andExpect(jsonPath("$.debts", hasSize(0)))
                .andExpect(jsonPath("$.creditHistory", hasSize(0)));
    }

    @Test
    void staffAndAdminHaveNoAccessToCustomers() throws Exception {
        createUser("staff02", Role.STAFF, branch);
        createUser("admin01", Role.ADMIN, null);
        for (String username : new String[] {"staff02", "admin01"}) {
            String token = signIn(username);
            send(get("/api/customers"), token)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
            send(post("/api/customers"), token, customer("Garage Hùng", null)).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer", Long.class)).isZero();
    }

    private Map<String, Object> customer(String name, String phone) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerName", name);
        body.put("phone", phone);
        body.put("address", "Hà Nội");
        body.put("taxCode", "0101234567");
        return body;
    }

    private long createStore(String code, boolean centralWarehouse) {
        Long storeId = jdbc.queryForObject("INSERT INTO store (store_name, address, status) "
                + "VALUES (?, 'Hà Nội', 'ACTIVE') RETURNING id", Long.class, "Cửa hàng " + code);
        jdbc.update("INSERT INTO warehouse (store_id, warehouse_name, is_central) VALUES (?, ?, ?)",
                storeId, "Kho " + code, centralWarehouse);
        return storeId;
    }

    private long addProduct(String sku, String name) {
        Long categoryId = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES (?, 'MOTORCYCLE') RETURNING id", Long.class, "Nhóm " + sku);
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'Cái', ?) RETURNING id", Long.class, sku, name, categoryId, owner.getId());
    }

    private long order(long storeId, String status, String completionType) {
        return jdbc.queryForObject("INSERT INTO sales_order (store_id, status, completion_type, created_by) "
                + "VALUES (?, ?, ?, ?) RETURNING id", Long.class, storeId, status, completionType, owner.getId());
    }

    private long version(long orderId, int versionNo, long customerId, String nameSnapshot) {
        return jdbc.queryForObject("INSERT INTO sales_order_version (sales_order_id, version_no, customer_id, "
                        + "customer_name_snapshot, price_type, created_by) VALUES (?, ?, ?, ?, 'RETAIL', ?) RETURNING id",
                Long.class, orderId, versionNo, customerId, nameSnapshot, owner.getId());
    }

    private void line(long versionId, int lineNo, long productId, int requested, Integer delivered, long price) {
        jdbc.update("INSERT INTO sales_order_line (sales_order_version_id, line_no, product_id, requested_qty, "
                        + "delivered_qty, reference_price, actual_unit_price) VALUES (?, ?, ?, ?, ?, ?, ?)",
                versionId, lineNo, productId, requested, delivered, price, price);
    }

    /** A credit sale when {@code orderId} is given, otherwise an opening balance. */
    private long receivable(long customerId, long storeId, Long orderId, long amount) {
        return jdbc.queryForObject("INSERT INTO receivable (origin_type, customer_id, store_id, sales_order_id, "
                        + "original_amount, recognized_by, recognized_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, orderId == null ? "OPENING_BALANCE" : "CREDIT_SALE", customerId, storeId, orderId, amount,
                owner.getId(), LocalDateTime.now(clock));
    }

    private long payment(String type, Long orderId, Long receivableId, long amount, String status) {
        return jdbc.queryForObject("INSERT INTO payment (payment_type, sales_order_id, receivable_id, amount, "
                        + "payment_method, status, recorded_by, recorded_at) VALUES (?, ?, ?, ?, 'CASH', ?, ?, ?) RETURNING id",
                Long.class, type, orderId, receivableId, amount, status, owner.getId(), LocalDateTime.now(clock));
    }

    /** A document correction of one payment or one receivable. */
    private long adjustment(long storeId, String status, Long paymentId, Long receivableId) {
        return jdbc.queryForObject("INSERT INTO adjustment_request (adjustment_type, store_id, payment_id, receivable_id, "
                        + "reason, status, requested_by) VALUES ('DOCUMENT_CORRECTION', ?, ?, ?, 'Ghi sai', ?, ?) RETURNING id",
                Long.class, storeId, paymentId, receivableId, status, owner.getId());
    }

    private void amountLine(long adjustmentId, Long paymentId, Long receivableId, long change) {
        jdbc.update("INSERT INTO adjustment_amount_line (adjustment_request_id, payment_id, receivable_id, amount_change) "
                + "VALUES (?, ?, ?, ?)", adjustmentId, paymentId, receivableId, change);
    }

    private void unmet(long orderId, long productId, int quantity, String status) {
        jdbc.update("INSERT INTO unmet_demand (sales_order_id, product_id, unmet_qty, status, recorded_by, recorded_at, "
                        + "closed_at) VALUES (?, ?, ?, ?, ?, ?, ?)", orderId, productId, quantity, status, owner.getId(),
                LocalDateTime.now(clock), status.equals("CLOSED") ? LocalDateTime.now(clock) : null);
    }

    private void creditEvent(long customerId, String type, String reason, String at) {
        jdbc.update("INSERT INTO credit_eligibility_event (customer_id, event_type, reason, decided_by, decided_at) "
                + "VALUES (?, ?, ?, ?, ?::timestamp)", customerId, type, reason, owner.getId(), at);
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

    private ResultActions send(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, Map<String, Object> body)
            throws Exception {
        return send(request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), token);
    }

    private long createdId(ResultActions created) throws Exception {
        String body = created.andExpect(status().isCreated()).andExpect(header().exists(HttpHeaders.LOCATION))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
