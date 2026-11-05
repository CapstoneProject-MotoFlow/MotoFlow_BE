package fu.hl.g64.motorflow.account;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-38 Manage Branch Manager Permissions (Owner). */
class BranchManagerPermissionApiTest extends ApiTestUsers {

    private long store;
    private UserAccount manager;
    private UserAccount staff;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        store = createStore("Gia Lâm");
        long otherStore = createStore("Long Biên");
        createUser("owner", Role.OWNER, null);
        manager = createUser("bm", Role.BRANCH_MANAGER, store);
        createUser("bm2", Role.BRANCH_MANAGER, otherStore);
        staff = createUser("staff", Role.STAFF, store);
    }

    @Test
    void ownerListsBranchManagersByStore() throws Exception {
        String token = signIn("owner");
        mvc.perform(get("/api/v1/branch-managers").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", contains("bm", "bm2")))
                .andExpect(jsonPath("$[0].storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$[0].retailPriceEdit").value(false));
        mvc.perform(get("/api/v1/branch-managers?storeId={id}", store).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$[*].username", contains("bm")));
    }

    @Test
    void ownerGrantsAndRevokesBothPermissionsWithHistory() throws Exception {
        String token = signIn("owner");
        update(token, manager.getId(), true, true, "Phụ trách bảng giá")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogEdit").value(true))
                .andExpect(jsonPath("$.retailPriceEdit").value(true));
        update(token, manager.getId(), true, false, null)
                .andExpect(jsonPath("$.retailPriceEdit").value(false));

        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity_type = 'USER_ACCOUNT' AND entity_id = ? "
                + "AND action LIKE 'PERMISSION_%' ORDER BY id", String.class, manager.getId()))
                .containsExactly("PERMISSION_GRANTED", "PERMISSION_GRANTED", "PERMISSION_REVOKED");
        // The manager's next request already carries the new permissions (BR-03).
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, signIn("bm")))
                .andExpect(jsonPath("$.permissions", contains("CATALOG_EDIT")));
    }

    @Test
    void onlyBranchManagersGetPermissionsAndOnlyTheOwnerGivesThem() throws Exception {
        update(signIn("owner"), staff.getId(), true, false, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG88"));
        update(signIn("owner"), 9999, true, false, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG48"));
        update(signIn("bm"), manager.getId(), true, true, null)
                .andExpect(status().isForbidden());
    }

    private ResultActions update(String token, long id, boolean catalog,
                                 boolean price, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("catalogEdit", catalog, "retailPriceEdit", price));
        body.put("reason", reason);
        return mvc.perform(put("/api/v1/branch-managers/{id}/permissions", id).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }
}
