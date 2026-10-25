package fu.hl.g64.motorflow.store;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StoreApiTest extends IntegrationTest {

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
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabaseAndClock();
        owner = createUser("owner", Role.OWNER, null);
        token = signIn("owner");
    }

    @Test
    void createsAPlannedStoreWithItsOnlyWarehouse() throws Exception {
        Map<String, Object> body = new HashMap<>(store("ch01", true));
        body.put("storeName", " Cửa hàng ch01 ");
        createStore(body)
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.storeName").value("Cửa hàng ch01"))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.warehouse.name").value("Kho ch01"))
                .andExpect(jsonPath("$.warehouse.central").value(true))
                .andExpect(jsonPath("$.managers", hasSize(0)))
                .andExpect(jsonPath("$.productsInStock").value(0));

        Map<String, Object> warehouse = jdbc.queryForMap("SELECT address, phone FROM warehouse");
        assertThat(warehouse).containsEntry("address", "Hà Nội").containsEntry("phone", "0241234567");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE entity_type = 'STORE' "
                + "AND action = 'STORE_CREATED'", Long.class)).isEqualTo(owner.getId());
    }

    @Test
    void storeNameIsUniqueIgnoringCase() throws Exception {
        createStore(store("CH01", false)).andExpect(status().isCreated());
        createStore(store("ch01", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG49"))
                .andExpect(jsonPath("$.message").value("Tên cửa hàng Cửa hàng ch01 đã được sử dụng."));

        long other = createdId(createStore(store("CH02", false)));
        Map<String, Object> rename = update("PLANNED", false);
        rename.put("storeName", "CỬA HÀNG CH01");
        updateStore(other, rename)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG49"));
        rename.put("storeName", "Cửa hàng CH02");
        updateStore(other, rename).andExpect(status().isOk());
    }

    @Test
    void theChainHasExactlyOneCentralWarehouse() throws Exception {
        long central = createdId(createStore(store("CH01", true)));
        createStore(store("CH02", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG50"))
                .andExpect(jsonPath("$.message").value("Chuỗi đã có kho tổng tại cửa hàng Cửa hàng CH01; mỗi chuỗi chỉ có một kho tổng."));
        long branch = createdId(createStore(store("CH02", false)));

        updateStore(branch, update("ACTIVE", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG50"));
        updateStore(central, update("ACTIVE", false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG51"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse WHERE is_central", Long.class)).isEqualTo(1);
    }

    @Test
    void theDatabaseAlsoRejectsASecondCentralWarehouse() throws Exception {
        createStore(store("CH01", true)).andExpect(status().isCreated());
        long branch = createdId(createStore(store("CH02", false)));
        assertThatThrownBy(() -> jdbc.update("UPDATE warehouse SET is_central = TRUE WHERE store_id = ?", branch))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aStoreClosesOnlyWithNoStockAndNoActiveAccounts() throws Exception {
        long central = createdId(createStore(store("CH01", true)));
        long branch = createdId(createStore(store("CH02", false)));
        updateStore(branch, update("ACTIVE", false)).andExpect(status().isOk());

        updateStore(central, update("INACTIVE", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG65"));
        updateStore(branch, update("PLANNED", false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG68"));

        Long warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE store_id = ?", Long.class, branch);
        addStock(warehouseId, "SKU1", 0, 2);
        updateStore(branch, update("INACTIVE", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG66"))
                .andExpect(jsonPath("$.message").value(startsWith("Kho của cửa hàng còn 1 mã")));
        jdbc.update("UPDATE stock_balance SET in_transit_qty = 0");

        UserAccount staff = createUser("staff01", Role.STAFF, branch);
        createUser("bm01", Role.BRANCH_MANAGER, branch);
        updateStore(branch, update("INACTIVE", false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG67"))
                .andExpect(jsonPath("$.message").value(startsWith("Cửa hàng còn 2 tài khoản")));
        jdbc.update("UPDATE user_account SET status = 'LOCKED' WHERE store_id = ?", branch);

        updateStore(branch, update("INACTIVE", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        // Closed stores still take edits, and reopen without the closing checks.
        jdbc.update("UPDATE user_account SET status = 'ACTIVE' WHERE id = ?", staff.getId());
        updateStore(branch, update("INACTIVE", false)).andExpect(status().isOk());
        updateStore(branch, update("PLANNED", false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG68"));
        updateStore(branch, update("ACTIVE", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        long planned = createdId(createStore(store("CH03", false)));
        updateStore(planned, update("PLANNED", false)).andExpect(status().isOk());
        updateStore(planned, update("INACTIVE", false)).andExpect(status().isOk());
    }

    @Test
    void updatesTheStoreAndItsWarehouse() throws Exception {
        long id = createdId(createStore(store("CH01", false)));
        Map<String, Object> body = update("ACTIVE", false);
        body.put("address", "Đà Nẵng");
        body.put("warehouseName", "Kho Đà Nẵng");

        updateStore(id, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.storeName").value("Cửa hàng mới " + id))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.warehouse.name").value("Kho Đà Nẵng"));

        assertThat(jdbc.queryForObject("SELECT address FROM warehouse WHERE store_id = ?", String.class, id))
                .isEqualTo("Đà Nẵng");
        updateStore(9999L, update("ACTIVE", false))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG52"));
    }

    @Test
    void listShowsActiveManagersAndProductsInStockAndFilters() throws Exception {
        createStore(store("CH01", true)).andExpect(status().isCreated());
        long branch = createdId(createStore(store("CH02", false)));
        updateStore(branch, update("ACTIVE", false)).andExpect(status().isOk());
        createUser("bm01", Role.BRANCH_MANAGER, branch);
        UserAccount locked = createUser("bm02", Role.BRANCH_MANAGER, branch);
        jdbc.update("UPDATE user_account SET status = 'LOCKED' WHERE id = ?", locked.getId());
        Long warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE store_id = ?", Long.class, branch);
        addStock(warehouseId, "SKU1", 5, 0);
        addStock(warehouseId, "SKU2", 0, 3);

        listStores("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].storeName", contains("Cửa hàng CH01", "Cửa hàng mới " + branch)))
                .andExpect(jsonPath("$[1].managers[*].fullName", contains("Test bm01")))
                .andExpect(jsonPath("$[1].productsInStock").value(1));
        listStores("?status=ACTIVE").andExpect(jsonPath("$[*].id", contains((int) branch)));
        listStores("?central=true").andExpect(jsonPath("$[*].storeName", contains("Cửa hàng CH01")));
        listStores("?q=TEST BM01").andExpect(jsonPath("$[*].id", contains((int) branch)));
        listStores("?status=OPEN")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
    }

    @Test
    void invalidFieldsAreListed() throws Exception {
        createStore(Map.of("storeName", "", "central", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.storeName").exists())
                .andExpect(jsonPath("$.fieldErrors.address").exists())
                .andExpect(jsonPath("$.fieldErrors.warehouseName").exists());
    }

    @Test
    void branchManagersMayNotManageStores() throws Exception {
        long id = createdId(createStore(store("CH01", false)));
        createUser("bm01", Role.BRANCH_MANAGER, id);
        mvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, "Bearer " + signIn("bm01")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
    }

    private Map<String, Object> store(String code, boolean central) {
        return Map.of("storeName", "Cửa hàng " + code, "address", "Hà Nội", "phone", "0241234567",
                "warehouseName", "Kho " + code.strip(), "central", central);
    }

    private Map<String, Object> update(String status, boolean central) {
        return new HashMap<>(Map.of("address", "Hà Nội", "status", status,
                "warehouseName", "Kho mới", "central", central));
    }

    private void addStock(long warehouseId, String sku, int available, int inTransit) {
        Long categoryId = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES (?, 'MOTORCYCLE') RETURNING id", Long.class, "Nhóm " + sku);
        Long productId = jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, 'Phụ tùng', ?, 'Cái', ?) RETURNING id", Long.class, sku, categoryId, owner.getId());
        jdbc.update("INSERT INTO stock_balance (warehouse_id, product_id, available_qty, in_transit_qty) VALUES (?, ?, ?, ?)",
                warehouseId, productId, available, inTransit);
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

    private ResultActions listStores(String query) throws Exception {
        return mvc.perform(get("/api/stores" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions createStore(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/stores").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)));
    }

    /** Without a {@code storeName} in the body, the store is renamed to "Cửa hàng mới {id}" (names are unique). */
    private ResultActions updateStore(long id, Map<String, Object> body) throws Exception {
        Map<String, Object> withName = new HashMap<>(body);
        withName.putIfAbsent("storeName", "Cửa hàng mới " + id);
        return mvc.perform(put("/api/stores/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(withName)));
    }

    private long createdId(ResultActions created) throws Exception {
        String body = created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
