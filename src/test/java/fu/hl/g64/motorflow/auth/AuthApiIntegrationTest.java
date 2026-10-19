package fu.hl.g64.motorflow.auth;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.UserPermission;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.account.repository.UserPermissionRepository;
import fu.hl.g64.motorflow.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
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

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthApiIntegrationTest extends IntegrationTest {

    private static final String PASSWORD = "Owner2026pass";
    private static final String NEW_PASSWORD = "Fresh2026pass";
    private static final String COOKIE = "mf_refresh";
    /** A URL Staff may not open: before the password change it answers MSG13, after it MSG05 (wrong role). */
    private static final String PROTECTED_URL = "/api/stores";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private UserPermissionRepository permissions;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
    }

    @Nested
    class LogIn {

        @Test
        void returnsAccessTokenAndHttpOnlyRefreshCookieAndRecordsSignInTime() throws Exception {
            UserAccount owner = createUser("owner", Role.OWNER, null, false);

            MvcResult result = login("owner", PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(jsonPath("$.user.username").value("owner"))
                    .andExpect(jsonPath("$.user.role").value("OWNER"))
                    .andExpect(jsonPath("$.user.mustChangePassword").value(false))
                    .andReturn();

            Cookie cookie = result.getResponse().getCookie(COOKIE);
            assertThat(cookie).isNotNull();
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getPath()).isEqualTo("/api/auth");
            assertThat(cookie.getMaxAge()).isEqualTo((int) Duration.ofDays(7).toSeconds());

            UserAccount reloaded = users.findById(owner.getId()).orElseThrow();
            assertThat(reloaded.getLastLoginAt()).isEqualTo(LocalDateTime.now(clock));
            assertThat(reloaded.getFailedLoginCount()).isZero();
        }

        @Test
        void blankUsernameOrPasswordGivesMsg08() throws Exception {
            login("", PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG08"))
                    .andExpect(jsonPath("$.message").value("Vui lòng nhập tên đăng nhập và mật khẩu."));
            login("owner", "  ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG08"));
        }

        @Test
        void unknownUsernameGivesTheSameMsg09AsAWrongPassword() throws Exception {
            createUser("owner", Role.OWNER, null, false);

            login("nobody", PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG09"));
            login("owner", "Wrong2026pass").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG09"));
            assertThat(failedCount("owner")).isEqualTo(1);
        }

        @Test
        void fifthWrongPasswordLocksTheAccountForTenMinutes() throws Exception {
            createUser("owner", Role.OWNER, null, false);
            for (int i = 0; i < 4; i++) {
                login("owner", "Wrong2026pass").andExpect(jsonPath("$.code").value("MSG09"));
            }

            login("owner", "Wrong2026pass")
                    .andExpect(status().isLocked())
                    .andExpect(jsonPath("$.code").value("MSG12"))
                    .andExpect(jsonPath("$.message").value(
                            "Bạn đã nhập sai mật khẩu 5 lần liên tiếp. Tài khoản bị tạm khóa trong 10 phút."));

            // Even the right password is refused while the lock lasts.
            clock.advance(Duration.ofMinutes(9));
            login("owner", PASSWORD)
                    .andExpect(status().isLocked())
                    .andExpect(jsonPath("$.code").value("MSG11"))
                    .andExpect(jsonPath("$.message").value(
                            "Tài khoản đang tạm khóa do nhập sai mật khẩu nhiều lần. Vui lòng thử lại sau 1 phút."));

            clock.advance(Duration.ofMinutes(1));
            login("owner", PASSWORD).andExpect(status().isOk());
        }

        @Test
        void aSuccessfulSignInRestartsTheFailedAttemptCount() throws Exception {
            createUser("owner", Role.OWNER, null, false);
            for (int i = 0; i < 4; i++) {
                login("owner", "Wrong2026pass");
            }
            login("owner", PASSWORD).andExpect(status().isOk());

            for (int i = 0; i < 4; i++) {
                login("owner", "Wrong2026pass").andExpect(jsonPath("$.code").value("MSG09"));
            }
            assertThat(failedCount("owner")).isEqualTo(4);
        }

        @Test
        void accountLockedByAdminGivesMsg10OnlyWithTheRightPassword() throws Exception {
            UserAccount owner = createUser("owner", Role.OWNER, null, false);
            lockByAdmin(owner);

            login("owner", "Wrong2026pass").andExpect(jsonPath("$.code").value("MSG09"));
            login("owner", PASSWORD).andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("MSG10"));
        }

        @Test
        void endOfTheTemporaryLockDoesNotLiftAnAdminLock() throws Exception {
            UserAccount owner = createUser("owner", Role.OWNER, null, false);
            lockByAdmin(owner);
            for (int i = 0; i < 5; i++) {
                login("owner", "Wrong2026pass");
            }

            clock.advance(Duration.ofMinutes(10));
            login("owner", PASSWORD).andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("MSG10"));
        }
    }

    @Nested
    class TemporaryPassword {

        @Test
        void onlyMeAndChangePasswordAreOpenUntilThePasswordIsChanged() throws Exception {
            createUser("staff", Role.STAFF, createStore("CH01"), true);
            String token = accessToken(login("staff", PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                    .andReturn());

            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk());
            mvc.perform(get(PROTECTED_URL).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MSG13"));

            changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

            // Same token: the change applies from the next request.
            mvc.perform(get(PROTECTED_URL).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MSG05"));
            login("staff", NEW_PASSWORD).andExpect(jsonPath("$.user.mustChangePassword").value(false));
        }
    }

    @Nested
    class ChangePassword {

        @Test
        void rejectsWrongCurrentPasswordWeakPasswordAndTheSamePassword() throws Exception {
            createUser("owner", Role.OWNER, null, false);
            String token = accessToken(login("owner", PASSWORD).andReturn());

            changePassword(token, "Wrong2026pass", NEW_PASSWORD)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG42"));
            changePassword(token, PASSWORD, "onlyletters")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG43"));
            changePassword(token, PASSWORD, "Ab1")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG43"));
            changePassword(token, PASSWORD, PASSWORD)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG44"));
            changePassword(token, "", "")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"))
                    .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        }

        @Test
        void signsOutEveryOtherDevice() throws Exception {
            createUser("owner", Role.OWNER, null, false);
            String laptop = accessToken(login("owner", PASSWORD).andReturn());
            String phone = accessToken(login("owner", PASSWORD).andReturn());

            changePassword(laptop, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

            me(laptop).andExpect(status().isOk());
            me(phone).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG06"));
        }
    }

    @Nested
    class Sessions {

        @Test
        void requestWithoutOrWithABadTokenGivesMsg06() throws Exception {
            mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("MSG06"));
            me("not-a-jwt").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG06"));
        }

        @Test
        void accessTokenExpiresAfterFifteenMinutesAndRefreshGivesANewOne() throws Exception {
            createUser("owner", Role.OWNER, null, false);
            MvcResult signIn = login("owner", PASSWORD).andReturn();
            Cookie refresh = signIn.getResponse().getCookie(COOKIE);

            clock.advance(Duration.ofMinutes(16));
            me(accessToken(signIn)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG06"));

            MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(refresh))
                    .andExpect(status().isOk()).andReturn();
            me(accessToken(refreshed)).andExpect(status().isOk());
        }

        @Test
        void refreshRotatesTheTokenSoTheOldOneStopsWorking() throws Exception {
            createUser("owner", Role.OWNER, null, false);
            Cookie first = login("owner", PASSWORD).andReturn().getResponse().getCookie(COOKIE);

            Cookie second = mvc.perform(post("/api/auth/refresh").cookie(first))
                    .andExpect(status().isOk()).andReturn().getResponse().getCookie(COOKIE);

            assertThat(second.getValue()).isNotEqualTo(first.getValue());
            mvc.perform(post("/api/auth/refresh").cookie(first))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG06"));
            mvc.perform(post("/api/auth/refresh").cookie(second)).andExpect(status().isOk());
        }

        @Test
        void refreshWithoutCookieGivesMsg06() throws Exception {
            mvc.perform(post("/api/auth/refresh"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG06"));
        }

        @Test
        void logoutEndsTheSessionAtOnce() throws Exception {
            createUser("owner", Role.OWNER, null, false);
            MvcResult signIn = login("owner", PASSWORD).andReturn();
            String token = accessToken(signIn);
            Cookie refresh = signIn.getResponse().getCookie(COOKIE);

            MvcResult logout = mvc.perform(post("/api/auth/logout")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)).cookie(refresh))
                    .andExpect(status().isNoContent()).andReturn();

            assertThat(logout.getResponse().getCookie(COOKIE).getMaxAge()).isZero();
            me(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG06"));
            mvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
        }

        @Test
        void adminLockStopsASignedInUserAtTheNextRequest() throws Exception {
            UserAccount owner = createUser("owner", Role.OWNER, null, false);
            MvcResult signIn = login("owner", PASSWORD).andReturn();

            lockByAdmin(owner);

            me(accessToken(signIn)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG10"));
            mvc.perform(post("/api/auth/refresh").cookie(signIn.getResponse().getCookie(COOKIE)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MSG10"));
        }

        @Test
        void permissionChangesApplyWithoutSigningInAgain() throws Exception {
            Long storeId = createStore("CH01");
            UserAccount manager = createUser("bm01", Role.BRANCH_MANAGER, storeId, false);
            String token = accessToken(login("bm01", PASSWORD).andReturn());
            me(token).andExpect(jsonPath("$.storeId").value(storeId)).andExpect(jsonPath("$.permissions").isEmpty());

            permissions.save(UserPermission.grant(manager.getId(), PermissionCode.CATALOG_EDIT, manager.getId(),
                    LocalDateTime.now(clock)));

            me(token).andExpect(jsonPath("$.permissions[0]").value("CATALOG_EDIT"));
        }
    }

    private UserAccount createUser(String username, Role role, Long storeId, boolean temporaryPassword) {
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = passwordEncoder.encode(PASSWORD);
        UserAccount user = UserAccount.create(username, hash, "Test " + username, role, storeId, null, now);
        if (!temporaryPassword) {
            user.changePassword(hash, now);
        }
        return users.save(user);
    }

    private Long createStore(String code) {
        return jdbc.queryForObject("INSERT INTO store (store_code, store_name, address, status, updated_at) "
                + "VALUES (?, ?, 'Hà Nội', 'ACTIVE', now()) RETURNING id", Long.class, code, "Cửa hàng " + code);
    }

    private void lockByAdmin(UserAccount user) {
        jdbc.update("UPDATE user_account SET status = 'LOCKED' WHERE id = ?", user.getId());
    }

    private int failedCount(String username) {
        return jdbc.queryForObject("SELECT failed_login_count FROM user_account WHERE username = ?", Integer.class, username);
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)));
    }

    private ResultActions changePassword(String token, String current, String next) throws Exception {
        return mvc.perform(post("/api/auth/change-password")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
