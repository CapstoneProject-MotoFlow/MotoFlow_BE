package fu.hl.g64.motorflow.upload;

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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadApiTest extends IntegrationTest {

    private static final String PASSWORD = "Admin2026pass";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        Long store = jdbc.queryForObject("INSERT INTO store (store_name, address, status, updated_at) "
                + "VALUES ('Cửa hàng CH01', 'Hà Nội', 'ACTIVE', now()) RETURNING id", Long.class);
        createUser("owner", Role.OWNER, null);
        createUser("staff01", Role.STAFF, store);
        createUser("admin", Role.ADMIN, null);
    }

    @Test
    void ownerGetsAPublicProductImageSignature() throws Exception {
        signature("owner", "PRODUCT_IMAGE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value("https://api.cloudinary.com/v1_1/motoflow-test/image/upload"))
                .andExpect(jsonPath("$.apiKey").value("1234"))
                .andExpect(jsonPath("$.type").value("upload"))
                .andExpect(jsonPath("$.folder").value("motoflow/products"))
                .andExpect(jsonPath("$.signature").isNotEmpty())
                .andExpect(jsonPath("$.apiSecret").doesNotExist());
    }

    @Test
    void staffUploadsDocumentsPrivatelyButNotProductImages() throws Exception {
        signature("staff01", "ATTACHMENT")
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("private"));
        signature("staff01", "PRODUCT_IMAGE")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
    }

    @Test
    void adminDoesNotUploadAndAPurposeIsRequired() throws Exception {
        signature("admin", "ATTACHMENT")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        mvc.perform(post("/api/v1/uploads/signature").header(HttpHeaders.AUTHORIZATION, "Bearer " + signIn("owner"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
    }

    private ResultActions signature(String username, String purpose) throws Exception {
        return mvc.perform(post("/api/v1/uploads/signature").header(HttpHeaders.AUTHORIZATION, "Bearer " + signIn(username))
                .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"" + purpose + "\"}"));
    }

    private void createUser(String username, Role role, Long store) {
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = passwordEncoder.encode(PASSWORD);
        UserAccount user = UserAccount.create(username, hash, "Test " + username, role, store, null, now);
        user.changePassword(hash, now);
        users.save(user);
    }

    private String signIn(String username) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
