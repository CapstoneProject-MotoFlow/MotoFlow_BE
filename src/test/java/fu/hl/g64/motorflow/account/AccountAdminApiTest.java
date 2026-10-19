package fu.hl.g64.motorflow.account;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
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

class AccountAdminApiTest extends IntegrationTest {

    private static final String PASSWORD = "Admin2026pass";
    private static final String TEMPORARY = "Temp2026pass";
    private static final String OWN_PASSWORD = "Mine2026pass";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JsonMapper jsonMapper;

    private UserAccount admin;
    private String adminToken;
    private Long storeId;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabaseAndClock();
        admin = createUser("admin", Role.ADMIN, null);
        adminToken = signIn("admin", PASSWORD);
        storeId = createStore("CH01", "ACTIVE");
    }

    @Nested
    class CreateAccount {

        @Test
        void newEmployeeMustChangeTheTemporaryPasswordAtFirstSignIn() throws Exception {
            createAccount(Map.of("username", "Staff01", "temporaryPassword", TEMPORARY, "fullName", "Nguyễn Văn A",
                    "phone", "0912345678", "role", "STAFF", "storeId", storeId))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists(HttpHeaders.LOCATION))
                    .andExpect(jsonPath("$.username").value("staff01"))
                    .andExpect(jsonPath("$.role").value("STAFF"))
                    .andExpect(jsonPath("$.storeId").value(storeId))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.mustChangePassword").value(true))
                    .andExpect(jsonPath("$.temporaryPassword").doesNotExist());

            UserAccount staff = users.findAll().stream().filter(u -> u.getUsername().equals("staff01")).findFirst().orElseThrow();
            assertThat(staff.getCreatedBy()).isEqualTo(admin.getId());
            assertThat(staff.getPhone()).isEqualTo("0912345678");
            assertThat(jdbc.queryForObject("SELECT changed_by FROM permission_history WHERE user_id = ? "
                    + "AND change_type = 'ACCOUNT_CREATED'", Long.class, staff.getId())).isEqualTo(admin.getId());

            // First sign-in: allowed, but every business function is closed until the password is changed.
            String token = JsonPath.read(login("STAFF01", TEMPORARY)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                    .andReturn().getResponse().getContentAsString(), "$.accessToken");
            mvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG13"));

            mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("currentPassword", TEMPORARY, "newPassword", OWN_PASSWORD))))
                    .andExpect(status().isNoContent());
            // Past the password gate; Staff still may not manage stores.
            mvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        }

        @Test
        void ownerAndAdminAccountsHaveNoStore() throws Exception {
            createAccount(Map.of("username", "owner", "temporaryPassword", TEMPORARY, "fullName", "Nguyễn Thị Nga",
                    "role", "OWNER"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.storeId").doesNotExist());
        }

        @Test
        void storeMustMatchTheRole() throws Exception {
            createAccount(Map.of("username", "staff02", "temporaryPassword", TEMPORARY, "fullName", "B", "role", "STAFF"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG16"));
            createAccount(Map.of("username", "owner2", "temporaryPassword", TEMPORARY, "fullName", "C", "role", "OWNER",
                    "storeId", storeId))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG16"));
        }

        @Test
        void storeMustExistAndNotBeClosed() throws Exception {
            Long closed = createStore("CH09", "INACTIVE");
            createAccount(Map.of("username", "staff03", "temporaryPassword", TEMPORARY, "fullName", "D",
                    "role", "BRANCH_MANAGER", "storeId", 9999))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG46"));
            createAccount(Map.of("username", "staff03", "temporaryPassword", TEMPORARY, "fullName", "D",
                    "role", "BRANCH_MANAGER", "storeId", closed))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG46"));
        }

        @Test
        void usernameIsUniqueIgnoringCase() throws Exception {
            createAccount(Map.of("username", "Admin", "temporaryPassword", TEMPORARY, "fullName", "E", "role", "ADMIN"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MSG45"))
                    .andExpect(jsonPath("$.message").value("Tên đăng nhập admin đã được sử dụng."));
        }

        @Test
        void temporaryPasswordMustFollowThePasswordRules() throws Exception {
            createAccount(Map.of("username", "staff04", "temporaryPassword", "12345678", "fullName", "F",
                    "role", "STAFF", "storeId", storeId))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG43"));
        }

        @Test
        void invalidFieldsAreListed() throws Exception {
            createAccount(Map.of("username", "có dấu", "temporaryPassword", TEMPORARY, "fullName", "",
                    "email", "not-an-email", "role", "STAFF", "storeId", storeId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MSG02"))
                    .andExpect(jsonPath("$.fieldErrors.username").exists())
                    .andExpect(jsonPath("$.fieldErrors.fullName").exists())
                    .andExpect(jsonPath("$.fieldErrors.email").exists());
        }

        @Test
        void onlyAdminMayCreateAccounts() throws Exception {
            createUser("owner", Role.OWNER, null);
            String ownerToken = signIn("owner", PASSWORD);
            mvc.perform(post("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", "x123", "temporaryPassword", TEMPORARY, "fullName", "X",
                                    "role", "OWNER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MSG05"));
        }
    }

    @Nested
    class ResetPassword {

        @Test
        void issuesANewTemporaryPasswordAndSignsTheUserOutEverywhere() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);
            String staffToken = signIn("staff01", PASSWORD);
            // The user forgot the password and got temporarily locked.
            for (int i = 0; i < 5; i++) {
                login("staff01", "Wrong2026pass");
            }
            login("staff01", PASSWORD).andExpect(jsonPath("$.code").value("MSG11"));

            resetPassword(staff.getId(), Map.of("temporaryPassword", TEMPORARY, "reason", "Quên mật khẩu"))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + staffToken))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG06"));
            login("staff01", PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG09"));
            login("staff01", TEMPORARY).andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.mustChangePassword").value(true));

            Map<String, Object> audit = jdbc.queryForMap("SELECT action, actor_id, reason FROM audit_log "
                    + "WHERE entity_type = 'USER_ACCOUNT' AND entity_id = ?", staff.getId());
            assertThat(audit).containsEntry("action", "PASSWORD_RESET")
                    .containsEntry("actor_id", admin.getId())
                    .containsEntry("reason", "Quên mật khẩu");
        }

        @Test
        void doesNotLiftAnAdminLock() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);
            jdbc.update("UPDATE user_account SET status = 'LOCKED' WHERE id = ?", staff.getId());

            resetPassword(staff.getId(), Map.of("temporaryPassword", TEMPORARY)).andExpect(status().isNoContent());

            login("staff01", TEMPORARY).andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("MSG10"));
        }

        @Test
        void rejectsOwnAccountUnknownAccountAndWeakPassword() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);
            resetPassword(admin.getId(), Map.of("temporaryPassword", TEMPORARY))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG47"));
            resetPassword(9999L, Map.of("temporaryPassword", TEMPORARY))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG48"));
            resetPassword(staff.getId(), Map.of("temporaryPassword", "short"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG43"));
        }
    }

    @Nested
    class ListAccounts {

        @Test
        void showsStoreStateAndPermissionsMostRecentSignInFirst() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);
            UserAccount manager = createUser("bm01", Role.BRANCH_MANAGER, storeId);
            UserAccount tempLocked = createUser("staff02", Role.STAFF, storeId);
            createUser("owner", Role.OWNER, null);
            jdbc.update("INSERT INTO user_permission (user_id, permission_code, granted_by, granted_at) "
                    + "VALUES (?, 'CATALOG_EDIT', ?, now())", manager.getId(), admin.getId());
            jdbc.update("UPDATE user_account SET locked_until = ? WHERE id = ?",
                    LocalDateTime.now(clock).plusMinutes(10), tempLocked.getId());
            clock.advance(Duration.ofMinutes(5));
            signIn("staff01", PASSWORD);

            listAccounts("")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].username", contains("staff01", "admin", "bm01", "staff02", "owner")))
                    .andExpect(jsonPath("$[0].storeName").value("Cửa hàng CH01"))
                    .andExpect(jsonPath("$[0].state").value("ACTIVE"))
                    .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
            listAccounts("?role=BRANCH_MANAGER")
                    .andExpect(jsonPath("$[*].id", contains(manager.getId().intValue())))
                    .andExpect(jsonPath("$[0].permissions", contains("CATALOG_EDIT")));
            listAccounts("?state=TEMPORARILY_LOCKED")
                    .andExpect(jsonPath("$[*].username", contains("staff02")));
            listAccounts("?q=ch01&storeId=" + storeId)
                    .andExpect(jsonPath("$[*].username", contains("staff01", "bm01", "staff02")));
            listAccounts("?q=TEST STAFF01")
                    .andExpect(jsonPath("$[*].id", contains(staff.getId().intValue())));
            listAccounts("?state=SOMETHING")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
        }

        @Test
        void onlyAdminMayListAccounts() throws Exception {
            createUser("owner", Role.OWNER, null);
            mvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + signIn("owner", PASSWORD)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        }
    }

    @Nested
    class LockAccount {

        @Test
        void lockSignsTheUserOutEverywhereAndUnlockLetsThemBackIn() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);
            String staffToken = signIn("staff01", PASSWORD);

            adminPost("/api/accounts/{id}/lock", staff.getId(), Map.of("reason", "Nghỉ việc"))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + staffToken))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG06"));
            login("staff01", PASSWORD).andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("MSG10"));
            listAccounts("?state=LOCKED").andExpect(jsonPath("$[*].username", contains("staff01")));
            Map<String, Object> audit = jdbc.queryForMap("SELECT action, actor_id, reason FROM audit_log "
                    + "WHERE entity_type = 'USER_ACCOUNT' AND entity_id = ?", staff.getId());
            assertThat(audit).containsEntry("action", "ACCOUNT_LOCKED")
                    .containsEntry("actor_id", admin.getId())
                    .containsEntry("reason", "Nghỉ việc");

            adminPost("/api/accounts/{id}/unlock", staff.getId(), null).andExpect(status().isNoContent());

            login("staff01", PASSWORD).andExpect(status().isOk());
        }

        @Test
        void unlockAlsoLiftsTheTemporaryLock() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);
            for (int i = 0; i < 5; i++) {
                login("staff01", "Wrong2026pass");
            }
            login("staff01", PASSWORD).andExpect(jsonPath("$.code").value("MSG11"));

            adminPost("/api/accounts/{id}/unlock", staff.getId(), null).andExpect(status().isNoContent());

            login("staff01", PASSWORD).andExpect(status().isOk());
        }

        @Test
        void rejectsOwnAccountAndUnknownAccount() throws Exception {
            adminPost("/api/accounts/{id}/lock", admin.getId(), null)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG15"));
            adminPost("/api/accounts/{id}/lock", 9999L, null)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG48"));
        }
    }

    @Nested
    class UpdatePermissions {

        @Test
        void movesStaffToAnotherStoreAndKeepsTheChangeInTheHistory() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);
            Long otherStore = createStore("CH02", "PLANNED");

            updatePermissions(staff.getId(), Map.of("role", "STAFF", "storeId", otherStore, "reason", "Điều chuyển"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(otherStore))
                    .andExpect(jsonPath("$.storeName").value("Cửa hàng CH02"));

            Map<String, Object> entry = jdbc.queryForMap("SELECT old_value, new_value, reason, changed_by "
                    + "FROM permission_history WHERE user_id = ? AND change_type = 'STORE_CHANGED'", staff.getId());
            assertThat(entry).containsEntry("old_value", "CH01").containsEntry("new_value", "CH02")
                    .containsEntry("reason", "Điều chuyển").containsEntry("changed_by", admin.getId());
        }

        @Test
        void catalogAndRetailPricePermissionsGoWhenTheUserIsNoLongerABranchManager() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);

            updatePermissions(staff.getId(), Map.of("role", "BRANCH_MANAGER", "storeId", storeId, "catalogEdit", true))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("BRANCH_MANAGER"))
                    .andExpect(jsonPath("$.permissions", contains("CATALOG_EDIT")));
            // Granted by the Owner (BR-08).
            jdbc.update("INSERT INTO user_permission (user_id, permission_code, granted_by, granted_at) "
                    + "VALUES (?, 'RETAIL_PRICE_EDIT', ?, now())", staff.getId(), admin.getId());

            updatePermissions(staff.getId(), Map.of("role", "STAFF", "storeId", storeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions", hasSize(0)));

            assertThat(jdbc.queryForList("SELECT change_type || ':' || coalesce(permission_code, new_value) "
                    + "FROM permission_history WHERE user_id = ? AND change_type <> 'ACCOUNT_CREATED' ORDER BY id",
                    String.class, staff.getId()))
                    .containsExactly("ROLE_CHANGED:BRANCH_MANAGER", "PERMISSION_GRANTED:CATALOG_EDIT",
                            "ROLE_CHANGED:STAFF", "PERMISSION_REVOKED:CATALOG_EDIT", "PERMISSION_REVOKED:RETAIL_PRICE_EDIT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_permission", Long.class)).isZero();
        }

        @Test
        void rejectsInvalidChanges() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);
            Long closed = createStore("CH09", "INACTIVE");

            updatePermissions(admin.getId(), Map.of("role", "OWNER"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG15"));
            updatePermissions(staff.getId(), Map.of("role", "STAFF"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG16"));
            updatePermissions(staff.getId(), Map.of("role", "STAFF", "storeId", storeId, "catalogEdit", true))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG17"));
            updatePermissions(staff.getId(), Map.of("role", "STAFF", "storeId", closed))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG46"));
            updatePermissions(9999L, Map.of("role", "OWNER"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG48"));
        }

        @Test
        void assignedTasksGoBackToTheStoreOnlyAfterConfirmation() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);
            UserAccount manager = createUser("bm01", Role.BRANCH_MANAGER, storeId);
            Long otherStore = createStore("CH02", "ACTIVE");
            Long taskId = assignInspectionTask(staff.getId(), manager.getId());

            updatePermissions(staff.getId(), Map.of("role", "STAFF", "storeId", otherStore))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MSG18"))
                    .andExpect(jsonPath("$.message").value("Tài khoản đang được giao 1 chứng từ chưa ghi kết quả. "
                            + "Nếu tiếp tục, các chứng từ này sẽ trở về trạng thái chờ giao việc."));
            assertThat(users.findById(staff.getId()).orElseThrow().getStoreId()).isEqualTo(storeId);

            updatePermissions(staff.getId(), Map.of("role", "STAFF", "storeId", otherStore, "releaseAssignedTasks", true))
                    .andExpect(status().isOk());

            assertThat(jdbc.queryForObject("SELECT status FROM warehouse_task WHERE id = ?", String.class, taskId))
                    .isEqualTo("CANCELLED");
            assertThat(jdbc.queryForList("SELECT recipient_id FROM notification", Long.class))
                    .containsExactly(manager.getId());
        }
    }

    @Nested
    class ViewPermissionHistory {

        @Test
        void newestFirstWithTheNameOfWhoChangedIt() throws Exception {
            createAccount(Map.of("username", "staff01", "temporaryPassword", TEMPORARY, "fullName", "Nguyễn Văn A",
                    "role", "STAFF", "storeId", storeId)).andExpect(status().isCreated());
            long staffId = users.findAll().stream().filter(u -> u.getUsername().equals("staff01")).findFirst()
                    .orElseThrow().getId();
            clock.advance(Duration.ofMinutes(1));
            updatePermissions(staffId, Map.of("role", "BRANCH_MANAGER", "storeId", storeId, "reason", "Thăng chức"))
                    .andExpect(status().isOk());

            mvc.perform(get("/api/accounts/{id}/permission-history", staffId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].changeType", contains("ROLE_CHANGED", "ACCOUNT_CREATED")))
                    .andExpect(jsonPath("$[0].oldValue").value("STAFF"))
                    .andExpect(jsonPath("$[0].newValue").value("BRANCH_MANAGER"))
                    .andExpect(jsonPath("$[0].reason").value("Thăng chức"))
                    .andExpect(jsonPath("$[0].changedByName").value("Test admin"));
            mvc.perform(get("/api/accounts/{id}/permission-history", 9999L)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG48"));
        }
    }

    private UserAccount createUser(String username, Role role, Long store) {
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = passwordEncoder.encode(PASSWORD);
        UserAccount user = UserAccount.create(username, hash, "Test " + username, role, store, null, now);
        user.changePassword(hash, now);
        return users.save(user);
    }

    private Long createStore(String code, String status) {
        return jdbc.queryForObject("INSERT INTO store (store_code, store_name, address, status, updated_at) "
                + "VALUES (?, ?, 'Hà Nội', ?, now()) RETURNING id", Long.class, code, "Cửa hàng " + code, status);
    }

    private String signIn(String username, String password) throws Exception {
        MvcResult result = login(username, password).andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", username, "password", password))));
    }

    private ResultActions createAccount(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions resetPassword(long accountId, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/accounts/{id}/reset-password", accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions listAccounts(String query) throws Exception {
        return mvc.perform(get("/api/accounts" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken));
    }

    private ResultActions updatePermissions(long accountId, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/accounts/{id}/permissions", accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions adminPost(String url, long accountId, Map<String, Object> body) throws Exception {
        MockHttpServletRequestBuilder request = post(url, accountId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken);
        return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    /** An inspection task of a goods receipt request at the main test store; returns the task id. */
    private Long assignInspectionTask(long assigneeId, long managerId) {
        Long warehouseId = jdbc.queryForObject("INSERT INTO warehouse (store_id, warehouse_name) VALUES (?, 'Kho CH01') "
                + "RETURNING id", Long.class, storeId);
        Long supplierId = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC01', 'NCC') "
                + "RETURNING id", Long.class);
        Long requestId = jdbc.queryForObject("INSERT INTO goods_receipt_request (request_code, supplier_id, warehouse_id, "
                + "created_by) VALUES ('YCN01', ?, ?, ?) RETURNING id", Long.class, supplierId, warehouseId, managerId);
        return jdbc.queryForObject("INSERT INTO warehouse_task (task_type, goods_receipt_request_id, assigned_to, "
                + "assigned_by, assigned_at) VALUES ('INSPECTION', ?, ?, ?, now()) RETURNING id",
                Long.class, requestId, assigneeId, managerId);
    }

    private String json(Map<String, Object> body) {
        return jsonMapper.writeValueAsString(body);
    }
}
