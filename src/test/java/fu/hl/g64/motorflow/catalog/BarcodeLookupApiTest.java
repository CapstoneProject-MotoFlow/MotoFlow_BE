package fu.hl.g64.motorflow.catalog;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BR-77. */
class BarcodeLookupApiTest extends ApiTestUsers {

    private long central;
    private long branch;
    private long brake;
    private long chain;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        central = createStore("Kho tổng");
        branch = createStore("Long Biên");
        UserAccount owner = createUser("owner", Role.OWNER, null);
        createUser("admin", Role.ADMIN, null);
        createUser("bm", Role.BRANCH_MANAGER, branch);
        createUser("staff", Role.STAFF, branch);
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        brake = jdbc.queryForObject("INSERT INTO product (sku, part_name, brand, category_id, base_unit, created_by) "
                + "VALUES ('MA-PHANH', 'Má phanh trước', 'Honda', ?, 'cái', ?) RETURNING id", Long.class,
                category, owner.getId());
        chain = jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, status, created_by) "
                + "VALUES ('XICH-XE', 'Xích tải', ?, 'sợi', 'DISCONTINUED', ?) RETURNING id", Long.class,
                category, owner.getId());
        jdbc.update("INSERT INTO product_identifier (product_id, identifier_type, identifier_value) VALUES "
                + "(?, 'BARCODE', '8935001234567'), (?, 'OEM', '06455-KVB-901'), (?, 'BARCODE', '8935009999999')",
                brake, brake, chain);
        jdbc.update("INSERT INTO stock_balance (store_id, product_id, available_qty, updated_at) VALUES "
                + "(?, ?, 7, now()), (?, ?, 40, now())", branch, brake, central, brake);
    }

    @Test
    void exactBarcodeGivesThePartAndStockOfTheManagersStore() throws Exception {
        scan("bm", "8935001234567", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(brake))
                .andExpect(jsonPath("$.sku").value("MA-PHANH"))
                .andExpect(jsonPath("$.partName").value("Má phanh trước"))
                .andExpect(jsonPath("$.brand").value("Honda"))
                .andExpect(jsonPath("$.baseUnit").value("cái"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.barcode").value("8935001234567"))
                .andExpect(jsonPath("$.storeId").value(branch))
                .andExpect(jsonPath("$.availableQty").value(7));
    }

    @Test
    void spacesAroundTheCodeAreIgnored() throws Exception {
        scan("bm", "  8935001234567 ", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(brake))
                .andExpect(jsonPath("$.barcode").value("8935001234567"));
    }

    @Test
    void unknownOrPartialCodeIsNotFound() throws Exception {
        scan("bm", "0000", "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG91"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy phụ tùng có mã vạch 0000."));
        scan("bm", "893500123456", "").andExpect(status().isNotFound());
    }

    @Test
    void oemCodeIsNotABarcode() throws Exception {
        scan("bm", "06455-KVB-901", "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG91"));
    }

    @Test
    void staffAlwaysSeesTheirOwnStore() throws Exception {
        scan("staff", "8935001234567", "").andExpect(jsonPath("$.storeId").value(branch))
                .andExpect(jsonPath("$.availableQty").value(7));
        scan("staff", "8935001234567", "?storeId=" + central).andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(branch))
                .andExpect(jsonPath("$.availableQty").value(7));
    }

    @Test
    void ownerSeesStockOnlyOfAChosenStore() throws Exception {
        scan("owner", "8935001234567", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId", nullValue()))
                .andExpect(jsonPath("$.availableQty", nullValue()));
        scan("owner", "8935001234567", "?storeId=" + central)
                .andExpect(jsonPath("$.storeId").value(central))
                .andExpect(jsonPath("$.availableQty").value(40));
    }

    @Test
    void storeWithoutARowHasNothingAvailable() throws Exception {
        long other = createStore("Hoàng Mai");
        scan("owner", "8935001234567", "?storeId=" + other).andExpect(jsonPath("$.availableQty").value(0));
    }

    @Test
    void discontinuedPartIsReturnedWithItsStatus() throws Exception {
        scan("bm", "8935009999999", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(chain))
                .andExpect(jsonPath("$.status").value("DISCONTINUED"))
                .andExpect(jsonPath("$.availableQty").value(0));
    }

    @Test
    void adminIsForbidden() throws Exception {
        scan("admin", "8935001234567", "").andExpect(status().isForbidden());
    }

    private ResultActions scan(String username, String code, String query) throws Exception {
        return mvc.perform(get("/api/v1/products/barcode/{code}" + query, code)
                .header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }
}
