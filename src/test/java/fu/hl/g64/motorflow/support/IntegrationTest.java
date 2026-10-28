package fu.hl.g64.motorflow.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;

/**
 * Base for tests that run the whole application against a real PostgreSQL. The database is
 * started once per test run from Maven-downloaded binaries, so Docker is not needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class IntegrationTest {

    public static final Instant START = Instant.parse("2026-10-13T01:00:00Z");

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    /** Empties every table and puts the clock back to {@link #START}. */
    protected void resetDatabaseAndClock() {
        jdbc.execute("TRUNCATE audit_log, auth_session, permission_history, user_permission, notification, warehouse_task, "
                + "goods_receipt, goods_receipt_request, supplier, stock_balance, pricing_policy, product_identifier, "
                + "product_image, product_vehicle_model, vehicle_model, product, part_category, user_account, warehouse, store "
                + "RESTART IDENTITY CASCADE");
        clock.set(START);
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder()
                    // upper()/lower() must know Vietnamese letters on every machine, as on the server's postgres image;
                    // initdb otherwise takes the machine's locale, and under C/POSIX "CỬA" and "Cửa" stay different.
                    .setLocaleConfig("locale-provider", "builtin")
                    .setLocaleConfig("builtin-locale", "C.UTF-8")
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
    }
}
