package fu.hl.g64.motorflow.security;

import fu.hl.g64.motorflow.auth.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Signs and checks access tokens. A token holds only the user id and the session id; role and
 * permissions are read from the database on each request instead (BR-03).
 */
@Component
public class JwtService {

    private static final String SESSION_CLAIM = "sid";

    private final AuthProperties properties;
    private final Clock clock;
    private final SecretKey key;

    public JwtService(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(decodeSecret(properties.jwtSecret()));
    }

    public String issueAccessToken(long userId, long sessionId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(userId))
                .claim(SESSION_CLAIM, sessionId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Empty when the token is malformed, tampered with, expired or from another issuer. */
    public Optional<AccessTokenClaims> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            long userId = Long.parseLong(claims.getSubject());
            long sessionId = ((Number) claims.get(SESSION_CLAIM)).longValue();
            return Optional.of(new AccessTokenClaims(userId, sessionId));
        } catch (JwtException | IllegalArgumentException | ClassCastException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static byte[] decodeSecret(String secret) {
        byte[] bytes;
        try {
            bytes = Decoders.BASE64.decode(secret);
        } catch (RuntimeException e) {
            throw new IllegalStateException("motoflow.auth.jwt-secret must be Base64", e);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("motoflow.auth.jwt-secret must decode to at least 32 bytes (256 bits)");
        }
        return bytes;
    }

    public record AccessTokenClaims(long userId, long sessionId) {
    }
}
