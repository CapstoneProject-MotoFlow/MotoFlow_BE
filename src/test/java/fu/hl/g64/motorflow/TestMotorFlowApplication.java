package fu.hl.g64.motorflow;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs the app on a local embedded PostgreSQL, for team members without Docker or PostgreSQL:
 * {@code ./mvnw spring-boot:test-run}. Data is kept in .dev-postgres/ between runs; delete that
 * folder to start from an empty database.
 */
public class TestMotorFlowApplication {

    public static void main(String[] args) throws IOException {
        // DevTools would call main() again in a new class loader and start a second database.
        System.setProperty("spring.devtools.restart.enabled", "false");
        // Only applies when no profile is set explicitly.
        System.setProperty("spring.profiles.default", "dev");
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setDataDirectory(Path.of(".dev-postgres"))
                .setCleanDataDirectory(false)
                .setPort(5433)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (IOException ignored) {
                // the process is exiting anyway
            }
        }));
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "");
        SpringApplication.run(MotorFlowApplication.class, args);
    }
}
