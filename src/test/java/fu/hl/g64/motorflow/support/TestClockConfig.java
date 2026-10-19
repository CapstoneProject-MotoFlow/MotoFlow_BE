package fu.hl.g64.motorflow.support;

import fu.hl.g64.motorflow.common.config.ClockConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application clock with one the tests can move. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(IntegrationTest.START, ClockConfig.VIETNAM);
    }
}
