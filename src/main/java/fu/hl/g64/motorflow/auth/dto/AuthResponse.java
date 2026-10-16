package fu.hl.g64.motorflow.auth.dto;

/**
 * Returned by login and refresh. Keep {@code accessToken} in memory only (not localStorage)
 * and send it as "Authorization: Bearer ..."; the refresh token comes as an HttpOnly cookie.
 */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, CurrentUserResponse user) {

    public static AuthResponse bearer(String accessToken, long expiresInSeconds, CurrentUserResponse user) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds, user);
    }
}
