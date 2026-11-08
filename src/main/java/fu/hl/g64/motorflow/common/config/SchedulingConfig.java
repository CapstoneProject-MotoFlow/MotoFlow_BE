package fu.hl.g64.motorflow.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the daily jobs (JOB-02, JOB-03). Tests set {@code motoflow.jobs.enabled=false} and call {@code run()}. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "motoflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
