package fu.hl.g64.motorflow.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Settings under motoflow.cloudinary.* (see application.properties). Empty values leave uploads off,
 * so the app still starts on a machine without a Cloudinary account.
 */
@ConfigurationProperties("motoflow.cloudinary")
public record CloudinaryProperties(String cloudName, String apiKey, String apiSecret, Duration downloadUrlTtl) {

    public CloudinaryProperties {
        downloadUrlTtl = downloadUrlTtl == null ? Duration.ofMinutes(5) : downloadUrlTtl;
    }

    public boolean configured() {
        return StringUtils.hasText(cloudName) && StringUtils.hasText(apiKey) && StringUtils.hasText(apiSecret);
    }
}
