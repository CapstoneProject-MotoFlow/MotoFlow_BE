package fu.hl.g64.motorflow.common;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Verifies that all DataIntegrityViolationException handlers triggered after pre-checks still map concurrent or
 * batched PostgreSQL constraint violations to their exact MSG conflict codes when JDBC batching is active.
 */
class BatchDataIntegrityConflictTest extends ApiTestUsers {

    private UserAccount admin;
    private String adminToken;
    private String ownerToken;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabaseAndClock();
        admin = createUser("admin", Role.ADMIN, null);
        createUser("owner", Role.OWNER, null);
        adminToken = signIn("admin");
        ownerToken = signIn("owner");
    }

    @Test
    void supplierCreateAndBatchedUpdateMapUxSupplierNameCiToMsg78() throws Exception {
        MvcResult first = perform(post("/api/v1/suppliers"), ownerToken, Map.of(
                "supplierName", "An Phát",
                "contactName", "Anh An",
                "phone", "0241234568"));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        long supplierId = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult createConflict = whileAnotherTransactionHoldsTheRow(
                "INSERT INTO supplier (supplier_name) VALUES (?)",
                () -> perform(post("/api/v1/suppliers"), ownerToken, Map.of(
                        "supplierName", "Hưng Thịnh",
                        "contactName", "Anh Hưng",
                        "phone", "0241234569")),
                "hưng thịnh");
        assertConflictCode(createConflict, "MSG78");

        MvcResult updateConflict = whileAnotherTransactionHoldsTheRow(
                "INSERT INTO supplier (supplier_name) VALUES (?)",
                () -> perform(put("/api/v1/suppliers/{id}", supplierId), ownerToken, Map.of(
                        "supplierName", "Đông Á",
                        "contactName", "Anh Đông",
                        "phone", "0241234570")),
                "đông á");
        assertConflictCode(updateConflict, "MSG78");
    }

    @Test
    void accountCreateMapsConcurrentDuplicateUsernameToMsg45() throws Exception {
        MvcResult result = whileAnotherTransactionHoldsTheRow(
                "INSERT INTO user_account (username, password_hash, full_name, role, created_by) "
                        + "VALUES (?, 'hash', 'Concurrent Owner', 'OWNER', ?)",
                () -> perform(post("/api/v1/accounts"), adminToken, Map.of(
                        "username", "owner2",
                        "fullName", "Owner Two",
                        "role", "OWNER")),
                "owner2", admin.getId());
        assertConflictCode(result, "MSG45");
    }

    @Test
    void partCategoryCreateAndBatchedDescendantUpdateMapConflictToMsg58() throws Exception {
        MvcResult parentCreated = perform(post("/api/v1/part-categories"), ownerToken, Map.of(
                "categoryName", "Hệ thống phanh",
                "vehicleType", "MOTORCYCLE"));
        assertThat(parentCreated.getResponse().getStatus()).isEqualTo(201);
        long parentId = ((Number) JsonPath.read(parentCreated.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult childCreated = perform(post("/api/v1/part-categories"), ownerToken, Map.of(
                "categoryName", "Má phanh",
                "vehicleType", "MOTORCYCLE",
                "parentId", parentId));
        assertThat(childCreated.getResponse().getStatus()).isEqualTo(201);

        MvcResult createConflict = whileAnotherTransactionHoldsTheRow(
                "INSERT INTO part_category (category_name, vehicle_type) VALUES (?, 'MOTORCYCLE')",
                () -> perform(post("/api/v1/part-categories"), ownerToken, Map.of(
                        "categoryName", "Hệ thống điện",
                        "vehicleType", "MOTORCYCLE")),
                "Hệ thống điện");
        assertConflictCode(createConflict, "MSG58");

        // Changing vehicleType updates both parentId and its child in one JDBC batch; conflict on parent still yields MSG58.
        MvcResult updateConflict = whileAnotherTransactionHoldsTheRow(
                "INSERT INTO part_category (category_name, vehicle_type) VALUES (?, 'E_MOTORCYCLE')",
                () -> perform(put("/api/v1/part-categories/{id}", parentId), ownerToken, Map.of(
                        "categoryName", "Phanh xe điện",
                        "vehicleType", "E_MOTORCYCLE")),
                "Phanh xe điện");
        assertConflictCode(updateConflict, "MSG58");
    }

    @Test
    void productCreateMapsConcurrentDuplicateSkuToMsg53() throws Exception {
        long categoryId = jdbc.queryForObject(
                "INSERT INTO part_category (category_name, vehicle_type) VALUES ('Phanh', 'MOTORCYCLE') RETURNING id",
                Long.class);
        MvcResult result = whileAnotherTransactionHoldsTheRow(
                "INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                        + "VALUES (?, 'Existing Part', ?, 'Cái', ?)",
                () -> perform(post("/api/v1/products"), ownerToken, Map.of(
                        "sku", "SKU-BATCH-1",
                        "partName", "New Part",
                        "categoryId", categoryId,
                        "brand", "Honda",
                        "baseUnit", "Cái")),
                "SKU-BATCH-1", categoryId, admin.getId());
        assertConflictCode(result, "MSG53");
    }

    @Test
    void vehicleModelCreateAndBatchedUpdateMapConflictToMsg62() throws Exception {
        MvcResult modelCreated = perform(post("/api/v1/vehicle-models"), ownerToken, Map.of(
                "manufacturer", "Honda",
                "modelName", "Wave Alpha",
                "vehicleType", "MOTORCYCLE"));
        assertThat(modelCreated.getResponse().getStatus()).isEqualTo(201);
        long modelId = ((Number) JsonPath.read(modelCreated.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult createConflict = whileAnotherTransactionHoldsTheRow(
                "INSERT INTO vehicle_model (manufacturer, model_name, vehicle_type) VALUES (?, ?, 'MOTORCYCLE')",
                () -> perform(post("/api/v1/vehicle-models"), ownerToken, Map.of(
                        "manufacturer", "Yamaha",
                        "modelName", "Sirius",
                        "vehicleType", "MOTORCYCLE")),
                "Yamaha", "Sirius");
        assertConflictCode(createConflict, "MSG62");

        MvcResult updateConflict = whileAnotherTransactionHoldsTheRow(
                "INSERT INTO vehicle_model (manufacturer, model_name, vehicle_type) VALUES (?, ?, 'MOTORCYCLE')",
                () -> perform(put("/api/v1/vehicle-models/{id}", modelId), ownerToken, Map.of(
                        "manufacturer", "Suzuki",
                        "modelName", "Raider",
                        "vehicleType", "MOTORCYCLE")),
                "Suzuki", "Raider");
        assertConflictCode(updateConflict, "MSG62");
    }

    @Test
    void storeCreateMapsConcurrentDuplicateNameToMsg49() throws Exception {
        MvcResult result = whileAnotherTransactionHoldsTheRow(
                "INSERT INTO store (store_name, address, status) VALUES (?, 'Hà Nội', 'PLANNED')",
                () -> perform(post("/api/v1/stores"), ownerToken, Map.of(
                        "storeName", "Cửa hàng CH99",
                        "address", "Hà Nội",
                        "central", false)),
                "Cửa hàng CH99");
        assertConflictCode(result, "MSG49");
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andReturn();
    }

    private static void assertConflictCode(MvcResult result, String expectedCode) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(409);
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo(expectedCode);
    }
}
