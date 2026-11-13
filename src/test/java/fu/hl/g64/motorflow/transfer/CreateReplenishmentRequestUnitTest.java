package fu.hl.g64.motorflow.transfer;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static fu.hl.g64.motorflow.support.Report51Support.as;
import static fu.hl.g64.motorflow.support.Report51Support.expectError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-68: ReplenishmentRequestService.create, UTC001 to UTC004. */
@Tag("report51")
class CreateReplenishmentRequestUnitTest extends ApiTestUsers {

    private static final String URL = "/api/v1/replenishment-requests";
    private static final String NOTE = "  Het ma phanh  ";

    private UserAccount owner;
    private UserAccount centralManager;
    private long central;
    /** Store #2 of the specification. */
    private long branch;
    private String managerToken;
    private String staffToken;
    private long p1;
    private long p2;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabaseAndClock();
        central = createStore("Kho tong");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        branch = createStore("Long Bien");
        owner = createUser("owner", Role.OWNER, null);
        centralManager = createUser("bmkt", Role.BRANCH_MANAGER, central);
        createUser("bm", Role.BRANCH_MANAGER, branch);
        createUser("staff", Role.STAFF, branch);
        managerToken = signIn("bm");
        staffToken = signIn("staff");
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        p1 = product("MA-PHANH", "Ma phanh truoc", category);
        p2 = product("XICH-XE", "Xich tai", category);
        long p9 = product("DEN-CU", "Den cu", category);
        jdbc.update("UPDATE product SET status = 'DISCONTINUED', discontinued_at = now() WHERE id = ?", p9);
    }

    private long product(String sku, String name, long category) {
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?) RETURNING id", Long.class, sku, name, category, owner.getId());
    }

    private static Map<String, Object> line(long productId, Number requestedQty) {
        return Map.of("productId", productId, "requestedQty", requestedQty);
    }

    private String body(Object... lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("storeId", null);
        body.put("note", NOTE);
        body.put("lines", Collections.unmodifiableList(new ArrayList<>(Arrays.asList(lines))));
        return json(body);
    }

    private long requestCount() {
        return jdbc.queryForObject("SELECT count(*) FROM replenishment_request", Long.class);
    }

    @Test
    @DisplayName("UTC001 Normal: a Branch Manager requests two parts for their store")
    void utc001() throws Exception {
        mvc.perform(as(managerToken, post(URL), body(line(p1, 5), line(p2, 3))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestingStoreId").value(branch))
                .andExpect(jsonPath("$.status").value("AWAITING_COORDINATION"))
                .andExpect(jsonPath("$.note").value("Het ma phanh"))
                .andExpect(jsonPath("$.lines[*].sku", contains("MA-PHANH", "XICH-XE")));

        assertThat(jdbc.queryForMap("SELECT requesting_store_id, status, note FROM replenishment_request"))
                .containsEntry("requesting_store_id", branch)
                .containsEntry("status", "AWAITING_COORDINATION").containsEntry("note", "Het ma phanh");
        assertThat(jdbc.queryForList("SELECT product_id || ':' || requested_qty FROM replenishment_request_line "
                + "ORDER BY id", String.class)).containsExactly(p1 + ":5", p2 + ":3");
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = "
                + "'REPLENISHMENT_REQUESTED' AND ref_type = 'REPLENISHMENT_REQUEST'", Long.class))
                .containsExactlyInAnyOrder(owner.getId(), centralManager.getId());
    }

    @Test
    @DisplayName("UTC002 Abnormal: a fractional quantity is refused, not rounded")
    void utc002() throws Exception {
        mvc.perform(as(managerToken, post(URL), body(line(p1, 1.5))))
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG02"))
                .andExpect(jsonPath("$.fieldErrors['lines[0].requestedQty']").exists());

        assertThat(requestCount()).isZero();
    }

    @Test
    @DisplayName("UTC003 Abnormal: a null line is refused")
    void utc003() throws Exception {
        mvc.perform(as(managerToken, post(URL), body((Object) null)))
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG02"))
                .andExpect(jsonPath("$.fieldErrors['lines[0]']").exists());

        assertThat(requestCount()).isZero();
    }

    @Test
    @DisplayName("UTC004 Abnormal: Staff of the store cannot request")
    void utc004() throws Exception {
        mvc.perform(as(staffToken, post(URL), body(line(p1, 5), line(p2, 3))))
                .andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertThat(requestCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Long.class)).isZero();
    }
}
