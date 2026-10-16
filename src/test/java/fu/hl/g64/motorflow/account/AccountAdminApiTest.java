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
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
            mvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isNotFound());
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

    private String json(Map<String, Object> body) {
        return jsonMapper.writeValueAsString(body);
    }
}
