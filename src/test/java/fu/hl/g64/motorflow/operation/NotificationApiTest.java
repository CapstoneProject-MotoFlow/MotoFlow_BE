package fu.hl.g64.motorflow.operation;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-87 View Notifications. */
class NotificationApiTest extends ApiTestUsers {

    @Autowired
    private NotificationService notifications;

    private long store;
    private UserAccount manager;
    private UserAccount otherManager;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        store = createStore("Gia Lâm");
        long otherStore = createStore("Long Biên");
        createUser("owner", Role.OWNER, null);
        createUser("owner2", Role.OWNER, null);
        manager = createUser("bm", Role.BRANCH_MANAGER, store);
        otherManager = createUser("bm2", Role.BRANCH_MANAGER, otherStore);
        createUser("admin", Role.ADMIN, null);
    }

    @Test
    void usersSeeOnlyTheirOwnNotificationsNewestFirst() throws Exception {
        notifications.notifyStoreManagers(store, "STOCK_COUNT_REQUESTED", "Owner yêu cầu kiểm kê", "STOCK_COUNT_REQUEST", 7L);
        clock.advance(Duration.ofMinutes(1));
        notifications.notify(manager.getId(), "LOW_STOCK", "Má phanh dưới ngưỡng", "PRODUCT", 3L);
        notifications.notifyOwners("STOCK_COUNT_SUBMITTED", "Phiếu kiểm kê chờ duyệt", "STOCK_COUNT", 1L);

        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, signIn("bm")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].type", contains("LOW_STOCK", "STOCK_COUNT_REQUESTED")))
                .andExpect(jsonPath("$.items[1].refType").value("STOCK_COUNT_REQUEST"))
                .andExpect(jsonPath("$.items[1].refId").value(7))
                .andExpect(jsonPath("$.items[0].read").value(false));
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, signIn("bm2")))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, signIn("owner2")))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void markOneOrAllRead() throws Exception {
        notifications.notify(manager.getId(), "LOW_STOCK", "A", "PRODUCT", 1L);
        notifications.notify(manager.getId(), "LOW_STOCK", "B", "PRODUCT", 2L);
        notifications.notify(manager.getId(), "LOW_STOCK", "C", "PRODUCT", 3L);
        notifications.notify(otherManager.getId(), "LOW_STOCK", "D", "PRODUCT", 4L);
        long first = jdbc.queryForObject("SELECT id FROM notification WHERE message = 'A'", Long.class);
        long others = jdbc.queryForObject("SELECT id FROM notification WHERE message = 'D'", Long.class);
        String token = signIn("bm");

        mvc.perform(post("/api/v1/notifications/{id}/read", first).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").isNotEmpty());
        mvc.perform(get("/api/v1/notifications?unreadOnly=true").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.items[*].message", contains("C", "B")));

        // Someone else's notification looks like it does not exist.
        mvc.perform(post("/api/v1/notifications/{id}/read", others).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG87"));

        mvc.perform(post("/api/v1/notifications/read-all").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE NOT is_read", Integer.class)).isEqualTo(1);
    }

    @Test
    void adminHasNoNotifications() throws Exception {
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, signIn("admin")))
                .andExpect(status().isForbidden());
    }
}
