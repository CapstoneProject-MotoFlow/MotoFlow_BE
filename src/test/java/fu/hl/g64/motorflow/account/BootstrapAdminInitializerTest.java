package fu.hl.g64.motorflow.account;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.account.service.BootstrapAdminInitializer;
import fu.hl.g64.motorflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapAdminInitializerTest extends IntegrationTest {

    private static final String TEMPORARY_PASSWORD = "Bootstrap@2026";

    @Autowired
    private BootstrapAdminInitializer initializer;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        ReflectionTestUtils.setField(initializer, "username", "admin");
        ReflectionTestUtils.setField(initializer, "password", TEMPORARY_PASSWORD);
    }

    @Test
    void createsOneAdminWithATemporaryPasswordAndHistory() {
        initializer.run(null);
        initializer.run(null);

        assertThat(users.count()).isEqualTo(1);
        UserAccount admin = users.findAll().getFirst();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getStoreId()).isNull();
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(passwordEncoder.matches(TEMPORARY_PASSWORD, admin.getPasswordHash())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permission_history WHERE change_type = 'ACCOUNT_CREATED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void refusesToStartWithAWeakPassword() {
        ReflectionTestUtils.setField(initializer, "password", "admin");

        assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(users.count()).isZero();
    }
}
