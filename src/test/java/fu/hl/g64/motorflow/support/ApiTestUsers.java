package fu.hl.g64.motorflow.support;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Base for API tests that need signed-in users of each role and a few stores. */
public abstract class ApiTestUsers extends IntegrationTest {

    protected static final String PASSWORD = "Test2026pass!";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected UserAccountRepository users;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JsonMapper jsonMapper;

    protected long createStore(String name) {
        return jdbc.queryForObject("INSERT INTO store (store_name, address, status) VALUES (?, 'Hà Nội', 'ACTIVE') "
                + "RETURNING id", Long.class, name);
    }

    protected UserAccount createUser(String username, Role role, Long store) {
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = passwordEncoder.encode(PASSWORD);
        UserAccount user = UserAccount.create(username, hash, "Test " + username, role, store, null, now);
        user.changePassword(hash, now);
        return users.save(user);
    }

    protected String signIn(String username) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    protected String json(Object body) {
        return jsonMapper.writeValueAsString(body);
    }
}
