package fu.hl.g64.motorflow.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Settings under motoflow.auth.* (see application.properties). */
@Validated
@ConfigurationProperties("motoflow.auth")
public record AuthProperties(
        /* Base64 HMAC key of at least 32 bytes; production reads it from MOTOFLOW_JWT_SECRET. */
        @NotBlank String jwtSecret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        /* Absolute limit from sign-in; refreshing cannot extend a session past it. */
        @NotNull Duration sessionMaxLifetime,
        /* SEC-02: wrong passwords in a row before the temporary lock. */
        @Min(1) int maxFailedAttempts,
        @NotNull Duration lockDuration,
        @Valid @NotNull RefreshCookie refreshCookie) {

    public record RefreshCookie(@NotBlank String name, @NotBlank String path, boolean secure, @NotBlank String sameSite) {
    }
}
