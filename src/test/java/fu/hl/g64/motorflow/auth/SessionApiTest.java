package fu.hl.g64.motorflow.auth;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionApiTest extends IntegrationTest {

    private static final String PASSWORD = "Admin2026pass";
    private static final String PHONE = "Mozilla/5.0 (Linux; Android 14; SM-A546E) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36";
    private static final String LAPTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JsonMapper jsonMapper;

    private UserAccount owner;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        createUser("admin", Role.ADMIN);
        owner = createUser("owner01", Role.OWNER);
    }

    @Test
    void userSeesOwnDevicesAndSignsOutAnother() throws Exception {
        String phoneToken = signIn("owner01", PHONE);
        String laptopToken = signIn("owner01", LAPTOP);

        String body = mvc.perform(get("/api/v1/auth/sessions").header(HttpHeaders.AUTHORIZATION, bearer(laptopToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].device").value("Chrome 141 · Windows"))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].ipAddress").value("127.0.0.1"))
                .andExpect(jsonPath("$[1].device").value("Chrome 141 · Android 14"))
                .andExpect(jsonPath("$[1].current").value(false))
                .andReturn().getResponse().getContentAsString();
        Integer phoneSession = JsonPath.read(body, "$[1].id");

        mvc.perform(delete("/api/v1/auth/sessions/{id}", phoneSession).header(HttpHeaders.AUTHORIZATION, bearer(laptopToken)))
                .andExpect(status().isNoContent());

        // The phone's access token stops working at once; the laptop stays signed in.
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(phoneToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/sessions").header(HttpHeaders.AUTHORIZATION, bearer(laptopToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        // Signing out an ended session again gives MSG75.
        mvc.perform(delete("/api/v1/auth/sessions/{id}", phoneSession).header(HttpHeaders.AUTHORIZATION, bearer(laptopToken)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG75"));
    }

    @Test
    void anotherUsersSessionLooksNotFound() throws Exception {
        String adminToken = signIn("admin", LAPTOP);
        String ownerOnlyToken = signIn("owner01", PHONE);
        Long adminSession = jdbc.queryForObject("SELECT s.id FROM auth_session s JOIN user_account u ON u.id = s.user_id "
                + "WHERE u.username = 'admin'", Long.class);

        mvc.perform(delete("/api/v1/auth/sessions/{id}", adminSession).header(HttpHeaders.AUTHORIZATION, bearer(ownerOnlyToken)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG75"));
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk());
    }

    @Test
    void adminListsAnAccountsDevicesAndSignsOneOut() throws Exception {
        String adminToken = signIn("admin", LAPTOP);
        String phoneToken = signIn("owner01", PHONE);
        String ownerToken = signIn("owner01", LAPTOP);

        String body = mvc.perform(get("/api/v1/accounts/{id}/sessions", owner.getId()).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].device").value("Chrome 141 · Android 14"))
                .andReturn().getResponse().getContentAsString();
        Integer phoneSession = JsonPath.read(body, "$[1].id");

        mvc.perform(post("/api/v1/accounts/{id}/sessions/{sessionId}/sign-out", owner.getId(), phoneSession)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Mất điện thoại"))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(phoneToken)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT revoke_reason FROM auth_session WHERE id = ?", String.class, phoneSession))
                .isEqualTo("LOGOUT");
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_log WHERE entity_id = ? AND action = 'SESSION_SIGNED_OUT'",
                String.class, owner.getId())).isEqualTo("Mất điện thoại");

        mvc.perform(get("/api/v1/accounts/{id}/sessions", 999999).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG48"));
        // Only Admin manages other accounts' devices.
        mvc.perform(get("/api/v1/accounts/{id}/sessions", owner.getId()).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
    }

    private UserAccount createUser(String username, Role role) {
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = passwordEncoder.encode(PASSWORD);
        UserAccount user = UserAccount.create(username, hash, "Test " + username, role, null, null, now);
        user.changePassword(hash, now);
        return users.save(user);
    }

    private String signIn(String username, String userAgent) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.USER_AGENT, userAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private String json(Map<String, Object> body) {
        return jsonMapper.writeValueAsString(body);
    }
}
