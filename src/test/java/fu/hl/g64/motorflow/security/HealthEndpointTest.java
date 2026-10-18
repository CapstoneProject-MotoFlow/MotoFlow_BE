package fu.hl.g64.motorflow.security;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The health check that deploy-service on the servers waits for after starting a new image. */
class HealthEndpointTest extends IntegrationTest {

    private static final String PASSWORD = "Admin2026pass";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private HealthEndpoint healthEndpoint;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
    }

    @Test
    void anyoneMayCheckThatTheApplicationIsUpButSeesNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void healthIncludesTheDatabase() {
        // So /actuator/health turns DOWN (503) when PostgreSQL is unreachable.
        assertThat(healthEndpoint.healthForPath("db").getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void onlyTheHealthCheckIsPublic() throws Exception {
        mvc.perform(get("/actuator")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/db")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "beans", "configprops", "heapdump", "info", "loggers", "mappings", "metrics"})
    void otherActuatorEndpointsAreNotExposed(String endpoint) throws Exception {
        // Signed in as Admin, so the 404 comes from the endpoint being absent, not from security.
        mvc.perform(get("/actuator/" + endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + signInAsAdmin()))
                .andExpect(status().isNotFound());
    }

    private String signInAsAdmin() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = passwordEncoder.encode(PASSWORD);
        UserAccount admin = UserAccount.create("admin", hash, "Test admin", Role.ADMIN, null, null, now);
        admin.changePassword(hash, now);
        users.save(admin);
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }
}
