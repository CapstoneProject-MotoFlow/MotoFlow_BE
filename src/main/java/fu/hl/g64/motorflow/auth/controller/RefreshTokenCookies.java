package fu.hl.g64.motorflow.auth.controller;

import fu.hl.g64.motorflow.auth.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.util.Optional;

/**
 * The refresh token travels only in an HttpOnly cookie scoped to /api/auth, so page scripts
 * cannot read it (XSS) and other API calls do not carry it.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookies {

    private final AuthProperties properties;

    public Optional<String> read(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, properties.refreshCookie().name());
        return cookie != null && StringUtils.hasText(cookie.getValue()) ? Optional.of(cookie.getValue()) : Optional.empty();
    }

    public ResponseCookie issue(String refreshToken) {
        return build(refreshToken, properties.refreshTokenTtl());
    }

    public ResponseCookie clear() {
        return build("", Duration.ZERO);
    }

    private ResponseCookie build(String value, Duration maxAge) {
        AuthProperties.RefreshCookie cookie = properties.refreshCookie();
        return ResponseCookie.from(cookie.name(), value)
                .httpOnly(true)
                .secure(cookie.secure())
                .sameSite(cookie.sameSite())
                .path(cookie.path())
                .maxAge(maxAge)
                .build();
    }
}
