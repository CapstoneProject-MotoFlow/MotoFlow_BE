package fu.hl.g64.motorflow.account;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.dto.UpdatePermissionsRequest;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.account.service.AccountService;
import fu.hl.g64.motorflow.account.service.PermissionService;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.auth.service.PasswordPolicy;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountAdminApiTest extends IntegrationTest {

    private static final String PASSWORD = "Admin2026pass";
    private static final String OWN_PASSWORD = "Mine@2026pass";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private AccountService accountService;
    @Autowired
    private PermissionService permissionService;

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
        void theLocationOfANewAccountShowsItAndAnUnknownIdGivesMsg48() throws Exception {
            String location = createAccount(Map.of("username", "staff01", "fullName", "Nguyễn Văn A",
                    "role", "STAFF", "storeId", storeId))
                    .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

            mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("staff01"))
                    .andExpect(jsonPath("$.storeName").value("Cửa hàng CH01"))
                    .andExpect(jsonPath("$.permissions", hasSize(0)));
            mvc.perform(get("/api/accounts/999999").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG48"));
        }

        @Test
        void newEmployeeMustChangeTheGeneratedPasswordAtFirstSignIn() throws Exception {
            String body = createAccount(Map.of("username", "Staff01", "fullName", "Nguyễn Văn A",
                    "phone", "0912345678", "role", "STAFF", "storeId", storeId))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists(HttpHeaders.LOCATION))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andExpect(jsonPath("$.account.username").value("staff01"))
                    .andExpect(jsonPath("$.account.role").value("STAFF"))
                    .andExpect(jsonPath("$.account.storeId").value(storeId))
                    .andExpect(jsonPath("$.account.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.account.mustChangePassword").value(true))
                    .andReturn().getResponse().getContentAsString();
            String temporary = JsonPath.read(body, "$.temporaryPassword");
            assertThat(temporary).hasSize(12);
            assertThat(PasswordPolicy.isAcceptable(temporary, "staff01")).isTrue();

            UserAccount staff = users.findAll().stream().filter(u -> u.getUsername().equals("staff01")).findFirst().orElseThrow();
            // Only the hash is kept, so the password can never be shown again.
            assertThat(passwordEncoder.matches(temporary, staff.getPasswordHash())).isTrue();
            assertThat(staff.getCreatedBy()).isEqualTo(admin.getId());
            assertThat(staff.getPhone()).isEqualTo("0912345678");
            assertThat(jdbc.queryForObject("SELECT changed_by FROM permission_history WHERE user_id = ? "
                    + "AND change_type = 'ACCOUNT_CREATED'", Long.class, staff.getId())).isEqualTo(admin.getId());

            // First sign-in: allowed, but every business function is closed until the password is changed.
            String token = JsonPath.read(login("STAFF01", temporary)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                    .andReturn().getResponse().getContentAsString(), "$.accessToken");
            mvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG13"));

            mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("currentPassword", temporary, "newPassword", OWN_PASSWORD))))
                    .andExpect(status().isNoContent());
            // Past the password gate; Staff still may not manage stores.
            mvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        }

        @Test
        void ownerAndAdminAccountsHaveNoStore() throws Exception {
            createAccount(Map.of("username", "owner", "fullName", "Nguyễn Thị Nga", "role", "OWNER"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.account.storeId").doesNotExist());
        }

        @Test
        void storeMustMatchTheRole() throws Exception {
            createAccount(Map.of("username", "staff02", "fullName", "B", "role", "STAFF"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG16"));
            createAccount(Map.of("username", "owner2", "fullName", "C", "role", "OWNER",
                    "storeId", storeId))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG16"));
        }

        @Test
        void storeMustExistAndNotBeClosed() throws Exception {
            Long closed = createStore("CH09", "INACTIVE");
            createAccount(Map.of("username", "staff03", "fullName", "D",
                    "role", "BRANCH_MANAGER", "storeId", 9999))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG46"));
            createAccount(Map.of("username", "staff03", "fullName", "D",
                    "role", "BRANCH_MANAGER", "storeId", closed))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG46"));
        }

        @Test
        void usernameIsUniqueIgnoringCase() throws Exception {
            createAccount(Map.of("username", "Admin", "fullName", "E", "role", "ADMIN"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MSG45"))
                    .andExpect(jsonPath("$.message").value("Tên đăng nhập admin đã được sử dụng."));
        }

        @Test
        void everyAccountGetsItsOwnPassword() throws Exception {
            String first = JsonPath.read(createAccount(Map.of("username", "staff04", "fullName", "F", "role", "STAFF",
                    "storeId", storeId)).andReturn().getResponse().getContentAsString(), "$.temporaryPassword");
            String second = JsonPath.read(createAccount(Map.of("username", "staff05", "fullName", "G", "role", "STAFF",
                    "storeId", storeId)).andReturn().getResponse().getContentAsString(), "$.temporaryPassword");
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        void invalidFieldsAreListed() throws Exception {
            createAccount(Map.of("username", "có dấu", "fullName", "",
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
                            .content(json(Map.of("username", "x123", "fullName", "X",
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

            String temporary = JsonPath.read(resetPassword(staff.getId(), Map.of("reason", "Quên mật khẩu"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(), "$.temporaryPassword");
            assertThat(PasswordPolicy.isAcceptable(temporary, "staff01")).isTrue();

            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + staffToken))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG06"));
            login("staff01", PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG09"));
            login("staff01", temporary).andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.mustChangePassword").value(true));

            Map<String, Object> audit = jdbc.queryForMap("SELECT action, actor_id, reason FROM audit_log "
                    + "WHERE entity_type = 'USER_ACCOUNT' AND entity_id = ? AND action = 'PASSWORD_RESET'", staff.getId());
            assertThat(audit).containsEntry("action", "PASSWORD_RESET")
                    .containsEntry("actor_id", admin.getId())
                    .containsEntry("reason", "Quên mật khẩu");
        }

        @Test
        void doesNotLiftAnAdminLock() throws Exception {
            UserAccount staff = createUser("staff01", Role.STAFF, storeId);
            jdbc.update("UPDATE user_account SET status = 'LOCKED' WHERE id = ?", staff.getId());

            // No body: the reason is optional.
            String temporary = JsonPath.read(adminPost("/api/accounts/{id}/reset-password", staff.getId(), null)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.temporaryPassword");

            login("staff01", temporary).andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("MSG10"));
        }

        @Test
        void rejectsOwnAccountAndUnknownAccount() throws Exception {
            resetPassword(admin.getId(), Map.of())
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG47"));
            resetPassword(9999L, Map.of())
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG48"));
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

    /** Errors raised by Spring itself use the same body and MSG codes as the rest of the API. */
    @Nested
    class ErrorBodies {

        @Test
        void unknownUrlAndWrongMethodGiveMsg70AndMsg71() throws Exception {
            mvc.perform(get("/api/no-such-thing").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG70"))
                    .andExpect(jsonPath("$.message").value("Không tìm thấy chức năng được yêu cầu."));
            mvc.perform(delete("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("MSG71"))
                    .andExpect(jsonPath("$.message").value("Chức năng này không hỗ trợ phương thức DELETE."));
        }

        @Test
        void unsupportedBodyTypeAndBrokenJsonGiveMsg02() throws Exception {
            mvc.perform(post("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.TEXT_PLAIN).content("staff01"))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("MSG02"));
            mvc.perform(post("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"))
                    .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        }

        @Test
        void wrongValuesNameTheirField() throws Exception {
            createAccount(Map.of("username", "staff01", "fullName", "A", "role", "KING"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"))
                    .andExpect(jsonPath("$.fieldErrors.role").value("Giá trị không hợp lệ."));
            listAccounts("?role=KING")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"))
                    .andExpect(jsonPath("$.fieldErrors.role").value("Giá trị không hợp lệ."));
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
                    + "WHERE entity_type = 'USER_ACCOUNT' AND entity_id = ? AND action = 'ACCOUNT_LOCKED'", staff.getId());
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
            assertThat(entry).containsEntry("old_value", storeId.toString()).containsEntry("new_value", otherStore.toString())
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
        void grantingAndRemovingTheAdminRoleIsKeptInTheHistory() throws Exception {
            String body = createAccount(Map.of("username", "admin02", "fullName", "Admin hai", "role", "ADMIN"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            long secondAdmin = ((Number) JsonPath.read(body, "$.account.id")).longValue();

            updatePermissions(secondAdmin, Map.of("role", "OWNER")).andExpect(status().isOk());

            assertThat(jdbc.queryForList("SELECT change_type || ':' || coalesce(old_value, '') || '>' || new_value "
                    + "FROM permission_history WHERE user_id = ? ORDER BY id", String.class, secondAdmin))
                    .containsExactly("ACCOUNT_CREATED:>ADMIN", "ROLE_CHANGED:ADMIN>OWNER");
        }

        @Test
        void theLastActiveAdminCannotBeLockedOrDemoted() throws Exception {
            // Reachable only when two Admins act on each other at once: the caller here is already locked.
            UserAccount caller = createUser("admin02", Role.ADMIN, null);
            caller.lockByAdmin(LocalDateTime.now(clock));
            users.save(caller);
            AuthenticatedUser lockedCaller = new AuthenticatedUser(caller.getId(), 0, "admin02", "Admin hai",
                    Role.ADMIN, null, Set.of(), false);

            assertThatThrownBy(() -> accountService.lockAccount(lockedCaller, admin.getId(), null))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG69"));
            assertThatThrownBy(() -> permissionService.updatePermissions(lockedCaller, admin.getId(),
                    new UpdatePermissionsRequest(Role.OWNER, null, false, null, false)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG69"));
            assertThat(users.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
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
            createAccount(Map.of("username", "staff01", "fullName", "Nguyễn Văn A",
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
        return jdbc.queryForObject("INSERT INTO store (store_name, address, status, updated_at) "
                + "VALUES (?, 'Hà Nội', ?, now()) RETURNING id", Long.class, "Cửa hàng " + code, status);
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

    /** An inspection task of a goods receipt at the main test store; returns the task id. */
    private Long assignInspectionTask(long assigneeId, long managerId) {
        Long warehouseId = jdbc.queryForObject("INSERT INTO warehouse (store_id, warehouse_name) VALUES (?, 'Kho CH01') "
                + "RETURNING id", Long.class, storeId);
        Long supplierId = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC01', 'NCC') "
                + "RETURNING id", Long.class);
        Long requestId = jdbc.queryForObject("INSERT INTO goods_receipt_request (supplier_id, warehouse_id, created_by) "
                + "VALUES (?, ?, ?) RETURNING id", Long.class, supplierId, warehouseId, managerId);
        Long receiptId = jdbc.queryForObject("INSERT INTO goods_receipt (receipt_type, goods_receipt_request_id, "
                + "delivery_document_no, arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, 'PGH01', now()) RETURNING id",
                Long.class, requestId);
        return jdbc.queryForObject("INSERT INTO warehouse_task (task_type, goods_receipt_id, assigned_to, "
                + "assigned_by, assigned_at) VALUES ('INSPECTION', ?, ?, ?, now()) RETURNING id",
                Long.class, receiptId, assigneeId, managerId);
    }

    private String json(Map<String, Object> body) {
        return jsonMapper.writeValueAsString(body);
    }
}
