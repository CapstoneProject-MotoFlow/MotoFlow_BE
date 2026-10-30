package fu.hl.g64.motorflow.auth.entity;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One signed-in device. Only the SHA-256 hash of its refresh token is stored. */
@Entity
@Table(name = "auth_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthSession {

    public enum RevokeReason { LOGOUT, PASSWORD_CHANGED, PASSWORD_RESET, ACCOUNT_LOCKED, REFRESH_TOKEN_REUSED, SIGNED_OUT_BY_ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserAccount user;

    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = 64)
    private String refreshTokenHash;

    /** The token replaced by the last rotation; seeing it again means it was copied (reuse detection). */
    @Column(name = "previous_refresh_token_hash", length = 64)
    private String previousRefreshTokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_refreshed_at", nullable = false)
    private LocalDateTime lastRefreshedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 30)
    private RevokeReason revokeReason;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    public static AuthSession open(UserAccount user, String refreshTokenHash, LocalDateTime now,
                                   LocalDateTime expiresAt, String userAgent, String ipAddress) {
        AuthSession session = new AuthSession();
        session.user = user;
        session.refreshTokenHash = refreshTokenHash;
        session.createdAt = now;
        session.lastRefreshedAt = now;
        session.expiresAt = expiresAt;
        session.userAgent = truncate(userAgent, 255);
        session.ipAddress = truncate(ipAddress, 45);
        return session;
    }

    public boolean isActive(LocalDateTime now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /** Each refresh replaces the token, so a stolen old token stops working. */
    public void rotate(String newRefreshTokenHash, LocalDateTime now, LocalDateTime newExpiresAt) {
        previousRefreshTokenHash = refreshTokenHash;
        refreshTokenHash = newRefreshTokenHash;
        lastRefreshedAt = now;
        expiresAt = newExpiresAt;
    }

    public void revoke(LocalDateTime now, RevokeReason reason) {
        if (revokedAt == null) {
            revokedAt = now;
            revokeReason = reason;
        }
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
