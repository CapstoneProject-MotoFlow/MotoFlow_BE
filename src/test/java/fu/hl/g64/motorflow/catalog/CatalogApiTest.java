package fu.hl.g64.motorflow.catalog;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
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

class CatalogApiTest extends IntegrationTest {

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
    /** CH01 holds the central warehouse; CH02 is a branch. */
    private long central;
    private long branch;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabaseAndClock();
        owner = createUser("owner", Role.OWNER, null);
        ownerToken = signIn("owner");
        central = createStore("CH01", true);
        branch = createStore("CH02", false);
    }

    @Nested
    class Categories {

        @Test
        void theLocationOfANewCategoryShowsItWithItsChildren() throws Exception {
            String location = send(post("/api/v1/part-categories"), ownerToken,
                    Map.of("categoryName", "Hệ thống phanh", "vehicleType", "MOTORCYCLE"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
            long brakes = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
            categoryId(Map.of("categoryName", "Bố thắng đĩa", "vehicleType", "MOTORCYCLE", "parentId", brakes));

            send(get(location), ownerToken)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.categoryName").value("Hệ thống phanh"))
                    .andExpect(jsonPath("$.children[*].categoryName", contains("Bố thắng đĩa")));
            send(get("/api/v1/part-categories/999999"), ownerToken)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG61"));
        }

        @Test
        void formATreeAndOnlyCategoriesWithoutChildrenTakeParts() throws Exception {
            long brakes = categoryId(Map.of("categoryName", "Hệ thống phanh", "vehicleType", "MOTORCYCLE"));
            long pads = categoryId(Map.of("categoryName", "Bố thắng đĩa", "vehicleType", "MOTORCYCLE", "parentId", brakes));

            send(get("/api/v1/part-categories"), ownerToken)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].categoryName").value("Hệ thống phanh"))
                    .andExpect(jsonPath("$[0].children[*].categoryName", contains("Bố thắng đĩa")));
            send(post("/api/v1/products"), ownerToken, product("SKU-1", brakes))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG55"));
            send(post("/api/v1/products"), ownerToken, product("SKU-1", pads)).andExpect(status().isCreated());

            send(post("/api/v1/part-categories"), ownerToken,
                    Map.of("categoryName", "Má phanh", "vehicleType", "MOTORCYCLE", "parentId", pads))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG60"));
            send(post("/api/v1/part-categories"), ownerToken,
                    Map.of("categoryName", "bố thắng ĐĨA", "vehicleType", "MOTORCYCLE", "parentId", brakes))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG58"));
            send(post("/api/v1/part-categories"), ownerToken,
                    Map.of("categoryName", "Xích", "vehicleType", "BICYCLE", "parentId", brakes))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG59"));
        }

        @Test
        void aCategoryCannotMoveUnderItselfAndItsBranchFollowsANewVehicleType() throws Exception {
            long brakes = categoryId(Map.of("categoryName", "Hệ thống phanh", "vehicleType", "MOTORCYCLE"));
            long pads = categoryId(Map.of("categoryName", "Bố thắng đĩa", "vehicleType", "MOTORCYCLE", "parentId", brakes));

            send(put("/api/v1/part-categories/{id}", brakes), ownerToken,
                    Map.of("categoryName", "Hệ thống phanh", "vehicleType", "MOTORCYCLE", "parentId", pads))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG59"));
            send(put("/api/v1/part-categories/{id}", brakes), ownerToken,
                    Map.of("categoryName", "Phanh", "vehicleType", "E_MOTORCYCLE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.children[0].vehicleType").value("E_MOTORCYCLE"));

            send(get("/api/v1/part-categories?vehicleType=E_MOTORCYCLE"), ownerToken)
                    .andExpect(jsonPath("$[*].categoryName", contains("Phanh")));
            send(get("/api/v1/part-categories?vehicleType=MOTORCYCLE"), ownerToken).andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        void aDeactivatedCategoryKeepsItsPartsButTakesNoNewOnes() throws Exception {
            long pads = categoryId(Map.of("categoryName", "Bố thắng đĩa", "vehicleType", "MOTORCYCLE"));
            long part = productId(product("SKU-1", pads));

            send(post("/api/v1/part-categories/{id}/deactivate", pads), ownerToken).andExpect(status().isNoContent());

            send(post("/api/v1/products"), ownerToken, product("SKU-2", pads))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG55"));
            send(put("/api/v1/products/{id}", part), ownerToken, product("SKU-1", pads)).andExpect(status().isOk());
            send(get("/api/v1/part-categories"), ownerToken).andExpect(jsonPath("$[0].status").value("INACTIVE"));
            send(post("/api/v1/part-categories/{id}/activate", pads), ownerToken).andExpect(status().isNoContent());
            send(post("/api/v1/products"), ownerToken, product("SKU-2", pads)).andExpect(status().isCreated());
        }
    }

    @Nested
    class VehicleModels {

        @Test
        void theLocationOfANewModelShowsIt() throws Exception {
            String location = send(post("/api/v1/vehicle-models"), ownerToken,
                    Map.of("manufacturer", "Honda", "modelName", "Wave Alpha", "vehicleType", "MOTORCYCLE"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

            send(get(location), ownerToken)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.modelName").value("Wave Alpha"));
            send(get("/api/v1/vehicle-models/999999"), ownerToken)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG63"));
        }

        @Test
        void areUniqueAndKeepTheirLinksWhenDeactivated() throws Exception {
            long airBlade = vehicleModelId(Map.of("manufacturer", "Honda", "modelName", "Air Blade 125",
                    "vehicleType", "MOTORCYCLE", "yearFrom", 2020, "yearTo", 2024));
            send(post("/api/v1/vehicle-models"), ownerToken, Map.of("manufacturer", "HONDA", "modelName", "air blade 125",
                    "vehicleType", "MOTORCYCLE", "yearFrom", 2020, "yearTo", 2024))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG62"));
            send(post("/api/v1/vehicle-models"), ownerToken, Map.of("manufacturer", "Honda", "modelName", "Vision",
                    "vehicleType", "MOTORCYCLE", "yearFrom", 2024, "yearTo", 2020))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG64"));
            vehicleModelId(Map.of("manufacturer", "Honda", "modelName", "Wave", "vehicleType", "MOTORCYCLE"));
            send(post("/api/v1/vehicle-models"), ownerToken,
                    Map.of("manufacturer", "Honda", "modelName", "Wave", "vehicleType", "MOTORCYCLE"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG62"));
            send(get("/api/v1/vehicle-models?q=air"), ownerToken)
                    .andExpect(jsonPath("$[*].id", contains((int) airBlade)));

            long pads = categoryId(Map.of("categoryName", "Bố thắng đĩa", "vehicleType", "MOTORCYCLE"));
            Map<String, Object> fitsAirBlade = product("SKU-1", pads);
            fitsAirBlade.put("vehicleModelIds", List.of(airBlade));
            long part = productId(fitsAirBlade);
            send(post("/api/v1/vehicle-models/{id}/deactivate", airBlade), ownerToken).andExpect(status().isNoContent());

            fitsAirBlade.put("sku", "SKU-2");
            send(post("/api/v1/products"), ownerToken, fitsAirBlade)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG56"));
            send(put("/api/v1/products/{id}", part), ownerToken, fitsAirBlade)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vehicleModels[0].status").value("INACTIVE"));
        }
    }

    @Nested
    class Products {

        @Test
        void createUpdateDiscontinueAndReactivate() throws Exception {
            long pads = categoryId(Map.of("categoryName", "Bố thắng đĩa", "vehicleType", "MOTORCYCLE"));
            long airBlade = vehicleModelId(Map.of("manufacturer", "Honda", "modelName", "Air Blade",
                    "vehicleType", "MOTORCYCLE"));
            Map<String, Object> body = product(" sku-001 ", pads);
            body.put("oemCodes", List.of("06435-K01-902"));
            body.put("barcodes", List.of("8930000000011"));
            body.put("vehicleModelIds", List.of(airBlade));
            body.put("imageUrls", List.of("https://img.example/a.jpg", "https://img.example/b.jpg"));

            long id = JsonPath.parse(send(post("/api/v1/products"), ownerToken, body)
                    .andExpect(status().isCreated())
                    .andExpect(header().exists(HttpHeaders.LOCATION))
                    .andExpect(jsonPath("$.sku").value("SKU-001"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.oemCodes", contains("06435-K01-902")))
                    .andExpect(jsonPath("$.barcodes", contains("8930000000011")))
                    .andExpect(jsonPath("$.imageUrls", contains("https://img.example/a.jpg", "https://img.example/b.jpg")))
                    .andExpect(jsonPath("$.vehicleModels[0].modelName").value("Air Blade"))
                    .andExpect(jsonPath("$.stock").doesNotExist())
                    .andReturn().getResponse().getContentAsString()).read("$.id", Long.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_balance", Long.class)).isZero();

            body.put("sku", "Sku-001");
            send(post("/api/v1/products"), ownerToken, body)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG53"));
            body.put("sku", "SKU-002");
            send(post("/api/v1/products"), ownerToken, body)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG54"));
            body.put("barcodes", List.of());
            send(post("/api/v1/products"), ownerToken, body).andExpect(status().isCreated());

            body.put("sku", "IGNORED");
            body.put("partName", "Bố thắng đĩa trước");
            body.put("oemCodes", List.of("06435-K01-903"));
            body.put("imageUrls", List.of("https://img.example/c.jpg"));
            send(put("/api/v1/products/{id}", id), ownerToken, body)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sku").value("SKU-001"))
                    .andExpect(jsonPath("$.partName").value("Bố thắng đĩa trước"))
                    .andExpect(jsonPath("$.oemCodes", contains("06435-K01-903")))
                    .andExpect(jsonPath("$.barcodes", hasSize(0)))
                    .andExpect(jsonPath("$.imageUrls", contains("https://img.example/c.jpg")));

            send(post("/api/v1/products/{id}/discontinue", id), ownerToken).andExpect(status().isNoContent());
            send(get("/api/v1/products/{id}", id), ownerToken)
                    .andExpect(jsonPath("$.status").value("DISCONTINUED"))
                    .andExpect(jsonPath("$.discontinuedAt").exists());
            send(get("/api/v1/products?status=ACTIVE"), ownerToken)
                    .andExpect(jsonPath("$.items[*].sku", contains("SKU-002")));
            send(post("/api/v1/products/{id}/reactivate", id), ownerToken).andExpect(status().isNoContent());
            send(get("/api/v1/products/{id}", id), ownerToken)
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.discontinuedAt").doesNotExist());
            send(get("/api/v1/products/{id}", 9999L), ownerToken)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG57"));
        }

        @Test
        void invalidFieldsAreListed() throws Exception {
            long pads = categoryId(Map.of("categoryName", "Bố thắng đĩa", "vehicleType", "MOTORCYCLE"));
            Map<String, Object> body = product("SKU-1", pads);
            body.remove("sku");
            send(post("/api/v1/products"), ownerToken, body)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
            body.put("sku", "có dấu");
            body.put("brand", "");
            body.put("imageUrls", List.of("https://a/1", "https://a/2", "https://a/3", "https://a/4"));
            send(post("/api/v1/products"), ownerToken, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MSG02"))
                    .andExpect(jsonPath("$.fieldErrors.sku").exists())
                    .andExpect(jsonPath("$.fieldErrors.brand").exists())
                    .andExpect(jsonPath("$.fieldErrors.imageUrls").exists());
        }

        @Test
        void searchShowsPriceAndStockOfTheViewedStore() throws Exception {
            long brakes = categoryId(Map.of("categoryName", "Hệ thống phanh", "vehicleType", "MOTORCYCLE"));
            long pads = categoryId(Map.of("categoryName", "Bố thắng đĩa", "vehicleType", "MOTORCYCLE", "parentId", brakes));
            long chains = categoryId(Map.of("categoryName", "Nhông xích", "vehicleType", "MOTORCYCLE"));
            long airBlade = vehicleModelId(Map.of("manufacturer", "Honda", "modelName", "Air Blade",
                    "vehicleType", "MOTORCYCLE"));
            Map<String, Object> padsBody = product("SKU-001", pads);
            padsBody.put("vehicleModelIds", List.of(airBlade));
            long padsPart = productId(padsBody);
            Map<String, Object> chainBody = product("SKU-002", chains);
            chainBody.put("oemCodes", List.of("40530-KWB-921"));
            long chainPart = productId(chainBody);
            long old = productId(product("SKU-003", chains));
            send(post("/api/v1/products/{id}/discontinue", old), ownerToken).andExpect(status().isNoContent());

            addStock(branch, padsPart, 5, "A-01");
            addStock(central, chainPart, 3, "B-02");
            LocalDateTime now = LocalDateTime.now(clock);
            addRetailPrice(padsPart, 90_000, now.minusDays(10), "SUPERSEDED");
            addRetailPrice(padsPart, 100_000, now.minusDays(1), "ACTIVE");
            addRetailPrice(padsPart, 120_000, now.plusDays(1), "SCHEDULED");

            createUser("staff01", Role.STAFF, branch);
            String staffToken = signIn("staff01");
            send(get("/api/v1/products"), staffToken)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(3))
                    .andExpect(jsonPath("$.items[*].sku", contains("SKU-001", "SKU-002", "SKU-003")))
                    .andExpect(jsonPath("$.items[0].retailPrice").value(100_000))
                    .andExpect(jsonPath("$.items[0].availableQty").value(5))
                    .andExpect(jsonPath("$.items[0].binLocation").value("A-01"))
                    .andExpect(jsonPath("$.items[0].categoryName").value("Bố thắng đĩa"))
                    .andExpect(jsonPath("$.items[1].availableQty").value(0))
                    .andExpect(jsonPath("$.items[1].oemCodes", contains("40530-KWB-921")));
            send(get("/api/v1/products?q=40530-kwb"), staffToken).andExpect(jsonPath("$.items[*].sku", contains("SKU-002")));
            send(get("/api/v1/products?categoryId=" + brakes), staffToken).andExpect(jsonPath("$.items[*].sku", contains("SKU-001")));
            send(get("/api/v1/products?vehicleModelId=" + airBlade), staffToken).andExpect(jsonPath("$.items[*].sku", contains("SKU-001")));
            send(get("/api/v1/products?inStockOnly=true"), staffToken).andExpect(jsonPath("$.items[*].sku", contains("SKU-001")));
            send(get("/api/v1/products?status=DISCONTINUED"), staffToken).andExpect(jsonPath("$.items[*].sku", contains("SKU-003")));
            send(get("/api/v1/products?size=1&page=1"), staffToken)
                    .andExpect(jsonPath("$.totalItems").value(3))
                    .andExpect(jsonPath("$.items[*].sku", contains("SKU-002")));
            send(get("/api/v1/products?status=OPEN"), staffToken)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));

            send(get("/api/v1/products/{id}", padsPart), staffToken)
                    .andExpect(jsonPath("$.retailPrice").value(100_000))
                    .andExpect(jsonPath("$.wholesalePrice").doesNotExist())
                    .andExpect(jsonPath("$.stock.storeId").value(branch))
                    .andExpect(jsonPath("$.stock.availableQty").value(5))
                    .andExpect(jsonPath("$.vehicleModels[0].modelName").value("Air Blade"));

            // Staff see only their store; Owner and the central warehouse's Branch Manager see every store (BR-03).
            send(get("/api/v1/products?storeId=" + central), staffToken)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
            send(get("/api/v1/products?storeId=" + central), ownerToken).andExpect(jsonPath("$.items[1].availableQty").value(3));
            send(get("/api/v1/products"), ownerToken).andExpect(jsonPath("$.items[0].availableQty").doesNotExist());
            createUser("bm01", Role.BRANCH_MANAGER, central);
            send(get("/api/v1/products?storeId=" + branch), signIn("bm01")).andExpect(jsonPath("$.items[0].availableQty").value(5));
        }
    }

    @Test
    void onlyOwnerAndBranchManagersWithTheCatalogPermissionChangeTheCatalog() throws Exception {
        UserAccount editor = createUser("bm01", Role.BRANCH_MANAGER, branch);
        jdbc.update("UPDATE user_account SET can_edit_catalog = TRUE WHERE id = ?", editor.getId());
        createUser("bm02", Role.BRANCH_MANAGER, branch);
        createUser("staff01", Role.STAFF, branch);
        createUser("admin", Role.ADMIN, null);
        Map<String, Object> category = Map.of("categoryName", "Phanh", "vehicleType", "MOTORCYCLE");

        send(post("/api/v1/part-categories"), signIn("bm01"), category).andExpect(status().isCreated());
        send(post("/api/v1/part-categories"), signIn("bm02"), category)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        String staffToken = signIn("staff01");
        send(post("/api/v1/vehicle-models"), staffToken,
                Map.of("manufacturer", "Honda", "modelName", "Wave", "vehicleType", "MOTORCYCLE"))
                .andExpect(status().isForbidden());
        send(get("/api/v1/part-categories"), staffToken).andExpect(status().isOk());
        send(get("/api/v1/products"), signIn("admin"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
    }

    private Map<String, Object> product(String sku, long categoryId) {
        return new HashMap<>(Map.of("sku", sku, "partName", "Bố thắng đĩa", "categoryId", categoryId,
                "brand", "Nissin", "baseUnit", "Cái"));
    }

    private long categoryId(Map<String, Object> body) throws Exception {
        return createdId(send(post("/api/v1/part-categories"), ownerToken, body));
    }

    private long vehicleModelId(Map<String, Object> body) throws Exception {
        return createdId(send(post("/api/v1/vehicle-models"), ownerToken, body));
    }

    private long productId(Map<String, Object> body) throws Exception {
        return createdId(send(post("/api/v1/products"), ownerToken, body));
    }

    private long createdId(ResultActions created) throws Exception {
        String body = created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    private long createStore(String code, boolean centralWarehouse) {
        return jdbc.queryForObject("INSERT INTO store (store_name, address, status, is_central) "
                + "VALUES (?, 'Hà Nội', 'ACTIVE', ?) RETURNING id", Long.class, "Cửa hàng " + code, centralWarehouse);
    }

    private void addStock(long storeId, long productId, int available, String bin) {
        jdbc.update("INSERT INTO stock_balance (store_id, product_id, available_qty, bin_location) "
                + "VALUES (?, ?, ?, ?)", storeId, productId, available, bin);
    }

    private void addRetailPrice(long productId, long price, LocalDateTime effectiveFrom, String status) {
        jdbc.update("INSERT INTO pricing_policy (policy_type, product_id, unit_price, effective_from, status, set_by, set_at) "
                + "VALUES ('RETAIL_PRICE', ?, ?, ?, ?, ?, ?)", productId, price, effectiveFrom, status, owner.getId(),
                LocalDateTime.now(clock));
    }

    private UserAccount createUser(String username, Role role, Long store) {
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = passwordEncoder.encode(PASSWORD);
        UserAccount user = UserAccount.create(username, hash, "Test " + username, role, store, null, now);
        user.changePassword(hash, now);
        return users.save(user);
    }

    private String signIn(String username) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token, Map<String, Object> body) throws Exception {
        return send(request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)), token);
    }
}
