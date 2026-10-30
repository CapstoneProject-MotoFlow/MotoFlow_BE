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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupplierApiTest extends IntegrationTest {

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
    private String ownerToken;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabaseAndClock();
        owner = createUser("owner", Role.OWNER, null);
        ownerToken = signIn("owner");
    }

    @Test
    void createsAnActiveSupplierWhoseCodeIsUniqueIgnoringCase() throws Exception {
        String location = send(post("/api/suppliers"), supplier(" ncc01 ", "Công ty Phụ tùng Minh Phát"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierCode").value("NCC01"))
                .andExpect(jsonPath("$.supplierName").value("Công ty Phụ tùng Minh Phát"))
                .andExpect(jsonPath("$.contactName").value("Anh Minh"))
                .andExpect(jsonPath("$.taxCode").value("0101234567"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith("/api/suppliers/");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'SUPPLIER_CREATED'", Long.class))
                .isEqualTo(owner.getId());

        send(post("/api/suppliers"), supplier("Ncc01", "Nhà cung cấp khác"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG72"))
                .andExpect(jsonPath("$.message").value("Mã nhà cung cấp NCC01 đã được sử dụng."));
        send(post("/api/suppliers"), supplier(null, "Không có mã"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
    }

    @Test
    void invalidFieldsAreListed() throws Exception {
        Map<String, Object> body = supplier("NCC 01", " ");
        body.put("email", "not-an-email");
        body.put("phone", "12ab");
        body.put("taxCode", "12345");
        send(post("/api/suppliers"), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.supplierCode").exists())
                .andExpect(jsonPath("$.fieldErrors.supplierName").value("Vui lòng nhập tên nhà cung cấp."))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists())
                .andExpect(jsonPath("$.fieldErrors.taxCode").exists());
    }

    @Test
    void anUpdateKeepsTheCodeAndTheSupplierCanBeDeactivated() throws Exception {
        long id = createdId(send(post("/api/suppliers"), supplier("NCC01", "Minh Phát")));
        Map<String, Object> body = supplier("NCC99", "Minh Phát mới");
        body.put("contactName", " ");
        body.put("taxCode", "0101234567-001");

        send(put("/api/suppliers/{id}", id), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierCode").value("NCC01"))
                .andExpect(jsonPath("$.supplierName").value("Minh Phát mới"))
                .andExpect(jsonPath("$.contactName").doesNotExist())
                .andExpect(jsonPath("$.taxCode").value("0101234567-001"));

        send(post("/api/suppliers/{id}/deactivate", id)).andExpect(status().isNoContent());
        send(get("/api/suppliers/{id}", id)).andExpect(jsonPath("$.supplier.status").value("INACTIVE"));
        send(post("/api/suppliers/{id}/deactivate", id)).andExpect(status().isNoContent());
        send(post("/api/suppliers/{id}/activate", id)).andExpect(status().isNoContent());
        send(get("/api/suppliers/{id}", id)).andExpect(jsonPath("$.supplier.status").value("ACTIVE"));
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity_type = 'SUPPLIER' ORDER BY id", String.class))
                .containsExactly("SUPPLIER_CREATED", "SUPPLIER_UPDATED", "SUPPLIER_DEACTIVATED", "SUPPLIER_ACTIVATED");

        send(put("/api/suppliers/{id}", 999999), body)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG73"));
        send(get("/api/suppliers/999999"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG73"));
    }

    @Test
    void theListFindsByCodeNameOrPhoneAndPages() throws Exception {
        Map<String, Object> first = supplier("NCC02", "Phụ tùng Hưng Thịnh");
        first.put("phone", "0912 345 678");
        long hungThinh = createdId(send(post("/api/suppliers"), first));
        createdId(send(post("/api/suppliers"), supplier("NCC01", "Nhớt Đông Á")));
        long closed = createdId(send(post("/api/suppliers"), supplier("NCC03", "Vỏ xe Sao Vàng")));
        send(post("/api/suppliers/{id}/deactivate", closed)).andExpect(status().isNoContent());

        send(get("/api/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].supplierCode", contains("NCC01", "NCC02", "NCC03")))
                .andExpect(jsonPath("$.totalItems").value(3));
        send(get("/api/suppliers?q=PHỤ TÙNG")).andExpect(jsonPath("$.items[*].id", contains((int) hungThinh)));
        send(get("/api/suppliers?q=0912345678")).andExpect(jsonPath("$.items[*].id", contains((int) hungThinh)));
        send(get("/api/suppliers?q=ncc0")).andExpect(jsonPath("$.items", hasSize(3)));
        send(get("/api/suppliers?status=INACTIVE")).andExpect(jsonPath("$.items[*].id", contains((int) closed)));
        send(get("/api/suppliers?size=2&page=1"))
                .andExpect(jsonPath("$.items[*].supplierCode", contains("NCC03")))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void theDetailShowsPurchaseOrdersDeliveriesAndDiscrepancies() throws Exception {
        long supplier = createdId(send(post("/api/suppliers"), supplier("NCC01", "Minh Phát")));
        long other = createdId(send(post("/api/suppliers"), supplier("NCC02", "Hưng Thịnh")));
        long store = createStore("CH01");
        long brake = addProduct("BRK-01", "Má phanh trước");
        long chain = addProduct("CHN-01", "Xích tải");

        long order = addPurchaseOrder(supplier, store, "FULLY_RECEIVED");
        jdbc.update("INSERT INTO goods_receipt_request_line (goods_receipt_request_id, product_id, ordered_qty, purchase_price) "
                + "VALUES (?, ?, 10, 50000), (?, ?, 4, 120000)", order, brake, order, chain);
        long newerOrder = addPurchaseOrder(supplier, store, "AWAITING_DELIVERY");
        addPurchaseOrder(other, store, "AWAITING_DELIVERY");
        Long delivery = jdbc.queryForObject("INSERT INTO goods_receipt (receipt_type, goods_receipt_request_id, "
                + "delivery_document_no, arrived_at, status, recorded_by, submitted_at, confirmed_by, confirmed_at) "
                + "VALUES ('SUPPLIER_DELIVERY', ?, 'PX-001', '2026-10-10 09:00', 'CONFIRMED', ?, '2026-10-10 10:00', ?, "
                + "'2026-10-10 11:00') RETURNING id", Long.class, order, owner.getId(), createUser("bm01", Role.BRANCH_MANAGER,
                store).getId());
        jdbc.update("INSERT INTO discrepancy_case (source_type, goods_receipt_id, product_id, short_qty, opened_at) "
                + "VALUES ('SUPPLIER', ?, ?, 2, '2026-10-10 11:00')", delivery, brake);

        send(get("/api/suppliers/{id}", supplier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplier.supplierCode").value("NCC01"))
                .andExpect(jsonPath("$.purchaseOrderCount").value(2))
                .andExpect(jsonPath("$.purchaseOrders[*].id", contains((int) newerOrder, (int) order)))
                .andExpect(jsonPath("$.purchaseOrders[1].storeName").value("Cửa hàng CH01"))
                .andExpect(jsonPath("$.purchaseOrders[1].lineCount").value(2))
                .andExpect(jsonPath("$.purchaseOrders[1].orderedValue").value(980000))
                .andExpect(jsonPath("$.purchaseOrders[0].lineCount").value(0))
                .andExpect(jsonPath("$.deliveryCount").value(1))
                .andExpect(jsonPath("$.deliveries[0].purchaseOrderId").value(order))
                .andExpect(jsonPath("$.deliveries[0].deliveryDocumentNo").value("PX-001"))
                .andExpect(jsonPath("$.deliveries[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.discrepancyCount").value(1))
                .andExpect(jsonPath("$.openDiscrepancyCount").value(1))
                .andExpect(jsonPath("$.discrepancies[0].sku").value("BRK-01"))
                .andExpect(jsonPath("$.discrepancies[0].shortQty").value(2))
                .andExpect(jsonPath("$.discrepancies[0].deliveryId").value(delivery));
        send(get("/api/suppliers/{id}", other))
                .andExpect(jsonPath("$.purchaseOrderCount").value(1))
                .andExpect(jsonPath("$.deliveries", hasSize(0)))
                .andExpect(jsonPath("$.openDiscrepancyCount").value(0));
    }

    @Test
    void onlyTheOwnerManagesSuppliers() throws Exception {
        long store = createStore("CH01");
        createUser("bm01", Role.BRANCH_MANAGER, store);
        createUser("admin01", Role.ADMIN, null);
        for (String username : new String[] {"bm01", "admin01"}) {
            String token = signIn(username);
            mvc.perform(get("/api/suppliers").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
            mvc.perform(post("/api/suppliers").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(supplier("NCC01", "Minh Phát"))))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supplier", Long.class)).isZero();
    }

    private Map<String, Object> supplier(String code, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("supplierCode", code);
        body.put("supplierName", name);
        body.put("contactName", "Anh Minh");
        body.put("phone", "0241234567");
        body.put("email", "lienhe@minhphat.vn");
        body.put("address", "Hà Nội");
        body.put("taxCode", "0101234567");
        return body;
    }

    private long createStore(String code) {
        return jdbc.queryForObject("INSERT INTO store (store_name, address, status) "
                + "VALUES (?, 'Hà Nội', 'ACTIVE') RETURNING id", Long.class, "Cửa hàng " + code);
    }

    private long addProduct(String sku, String name) {
        Long categoryId = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES (?, 'MOTORCYCLE') RETURNING id", Long.class, "Nhóm " + sku);
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'Cái', ?) RETURNING id", Long.class, sku, name, categoryId, owner.getId());
    }

    private long addPurchaseOrder(long supplierId, long storeId, String status) {
        return jdbc.queryForObject("INSERT INTO goods_receipt_request (supplier_id, store_id, status, created_by) "
                + "VALUES (?, ?, ?, ?) RETURNING id", Long.class, supplierId, storeId, status, owner.getId());
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

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, Object> body) throws Exception {
        return send(request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)));
    }

    private long createdId(ResultActions created) throws Exception {
        String body = created.andExpect(status().isCreated()).andExpect(header().exists(HttpHeaders.LOCATION))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
