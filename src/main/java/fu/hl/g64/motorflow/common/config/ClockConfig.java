package fu.hl.g64.motorflow.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Every "now" in the application comes from this clock, so tests can move time
 * (for example past the 10-minute sign-in lock) and all timestamps use Vietnam time.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Bean
    public Clock clock() {
        return Clock.system(VIETNAM);
    }
}
