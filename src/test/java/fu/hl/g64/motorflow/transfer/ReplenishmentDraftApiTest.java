package fu.hl.g64.motorflow.transfer;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PM 17/11: replenishment request drafts, saved on the server, seen only by their creator. */
class ReplenishmentDraftApiTest extends ApiTestUsers {

    private static final String REQUESTS = "/api/v1/replenishment-requests";
    private static final String DRAFTS = REQUESTS + "/drafts";

    private UserAccount owner;
    private UserAccount centralManager;
    private long central;
    private long branch;
    private long brake;
    private long chain;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        central = createStore("Kho tổng");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        branch = createStore("Long Biên");
        owner = createUser("owner", Role.OWNER, null);
        centralManager = createUser("bmkt", Role.BRANCH_MANAGER, central);
        createUser("bm", Role.BRANCH_MANAGER, branch);
        createUser("bm2", Role.BRANCH_MANAGER, branch);
        createUser("staff", Role.STAFF, branch);
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        brake = product("MA-PHANH", "Má phanh trước", category);
        chain = product("XICH-XE", "Xích tải", category);
    }

    @Test
    void aDraftIsListedOnlyForItsCreatorAndTellsNobody() throws Exception {
        long draft = saved("bm", body(null, "Nháp", line(brake, 3)));
        call("bm", get(DRAFTS + "/" + draft)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.storeName").value("Long Biên"))
                .andExpect(jsonPath("$.note").value("Nháp"))
                .andExpect(jsonPath("$.lines[*].sku", contains("MA-PHANH")));
        call("bm", get(DRAFTS)).andExpect(jsonPath("$.items[*].id", contains((int) draft)))
                .andExpect(jsonPath("$.items[0].lineCount").value(1))
                .andExpect(jsonPath("$.items[0].totalRequestedQty").value(3));
        call("bm", get(DRAFTS).param("q", "YBS-" + draft)).andExpect(jsonPath("$.items[*].id", contains((int) draft)));
        call("bm", get(DRAFTS).param("q", "Kho")).andExpect(jsonPath("$.items", empty()));

        call("bm2", get(DRAFTS)).andExpect(jsonPath("$.items", empty()));
        call("owner", get(DRAFTS)).andExpect(jsonPath("$.items", empty()));
        call("bm", get(REQUESTS)).andExpect(jsonPath("$.items", empty()));
        assertThat(notifications()).isZero();
    }

    @Test
    void someoneElsesDraftOrARealRequestIsNotFound() throws Exception {
        long draft = saved("bm", body(null, null, line(brake, 1)));
        for (MockHttpServletRequestBuilder request : List.of(get(DRAFTS + "/" + draft),
                json(put(DRAFTS + "/" + draft), body(null, null, line(chain, 1))),
                json(post(DRAFTS + "/" + draft + "/submit"), body(null, null, line(chain, 1))),
                delete(DRAFTS + "/" + draft))) {
            call("bm2", request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG95"));
        }
        call("staff", get(DRAFTS)).andExpect(status().isForbidden());

        call("bm", json(post(REQUESTS), body(null, null, line(brake, 1)))).andExpect(status().isCreated());
        long request = jdbc.queryForObject("SELECT max(id) FROM replenishment_request", Long.class);
        call("bm", get(DRAFTS + "/" + request)).andExpect(status().isNotFound());
        call("bm", delete(DRAFTS + "/" + request)).andExpect(status().isNotFound());
        assertThat(lineCount(draft)).isEqualTo(1);
    }

    @Test
    void savingReplacesTheWholeDraftCheckedAsANewRequest() throws Exception {
        long draft = saved("owner", body(branch, "Một", line(brake, 1)));
        call("owner", json(put(DRAFTS + "/" + draft), body(central, " Hai ", line(chain, 4), line(brake, 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestingStoreId").value(central))
                .andExpect(jsonPath("$.note").value("Hai"))
                .andExpect(jsonPath("$.lines[*].sku", contains("XICH-XE", "MA-PHANH")));
        // The Owner must choose a store, as for a new request.
        call("owner", json(post(DRAFTS), body(null, null, line(brake, 1)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        call("bm", json(post(DRAFTS), body(central, null, line(brake, 1)))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
    }

    @Test
    void aSubmittedDraftKeepsItsIdAndTellsTheCoordinators() throws Exception {
        long draft = saved("bm", body(null, null, line(brake, 1)));
        jdbc.update("UPDATE replenishment_request SET created_at = '2026-01-01 08:00' WHERE id = ?", draft);

        call("bm", json(post(DRAFTS + "/" + draft + "/submit"), body(null, "Gửi", line(brake, 5), line(chain, 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(draft))
                .andExpect(jsonPath("$.status").value("AWAITING_COORDINATION"))
                .andExpect(jsonPath("$.lines[*].requestedQty", contains(5, 2)));
        assertThat(jdbc.queryForObject("SELECT created_at > '2026-01-02' FROM replenishment_request WHERE id = ?",
                Boolean.class, draft)).isTrue();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = "
                + "'REPLENISHMENT_REQUESTED' AND ref_id = ?", Long.class, draft))
                .containsExactlyInAnyOrder(owner.getId(), centralManager.getId());

        call("bmkt", get(REQUESTS)).andExpect(jsonPath("$.items[*].id", contains((int) draft)));
        call("bm", get(DRAFTS)).andExpect(jsonPath("$.items", empty()));
        call("bm", json(post(DRAFTS + "/" + draft + "/submit"), body(null, null, line(brake, 1))))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRefusedSubmitLeavesTheDraftAsItWas() throws Exception {
        long draft = saved("bm", body(null, "Cũ", line(brake, 1)));
        jdbc.update("UPDATE product SET status = 'DISCONTINUED', discontinued_at = now() WHERE id = ?", chain);

        call("bm", json(post(DRAFTS + "/" + draft + "/submit"), body(null, "Mới", line(chain, 3))))
                .andExpect(status().isBadRequest());
        call("bm", get(DRAFTS + "/" + draft)).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.note").value("Cũ"))
                .andExpect(jsonPath("$.lines[*].sku", contains("MA-PHANH")));
        assertThat(notifications()).isZero();
    }

    @Test
    void aDeletedDraftIsGoneWithItsLines() throws Exception {
        long draft = saved("bm", body(null, null, line(brake, 1), line(chain, 1)));
        call("bm", delete(DRAFTS + "/" + draft)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM replenishment_request", Long.class)).isZero();
        assertThat(lineCount(draft)).isZero();
        call("bm", get(DRAFTS + "/" + draft)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------------------------

    private long saved(String username, Map<String, Object> body) throws Exception {
        String response = call(username, json(post(DRAFTS), body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private ResultActions call(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Map<String, Object> body)
            throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    @SafeVarargs
    private static Map<String, Object> body(Long storeId, String note, Map<String, Object>... lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("storeId", storeId);
        body.put("note", note);
        body.put("lines", List.of(lines));
        return body;
    }

    private static Map<String, Object> line(long productId, int requestedQty) {
        return Map.of("productId", productId, "requestedQty", requestedQty);
    }

    private long notifications() {
        return jdbc.queryForObject("SELECT count(*) FROM notification", Long.class);
    }

    private long lineCount(long requestId) {
        return jdbc.queryForObject("SELECT count(*) FROM replenishment_request_line WHERE replenishment_request_id = ?",
                Long.class, requestId);
    }

    private long product(String sku, String name, long category) {
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?) RETURNING id", Long.class, sku, name, category, owner.getId());
    }
}
