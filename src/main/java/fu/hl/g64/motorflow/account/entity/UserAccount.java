package fu.hl.g64.motorflow.account.entity;

import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

@Entity
@Table(name = "user_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "store_id")
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "failed_login_count", nullable = false)
    private short failedLoginCount;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * A new account always starts with a temporary password that must be changed
     * at the first sign-in (UC-08, SEC-03).
     */
    public static UserAccount create(String username, String temporaryPasswordHash, String fullName,
                                     Role role, Long storeId, Long createdBy, LocalDateTime now) {
        if (role.requiresStore() != (storeId != null)) {
            throw new IllegalArgumentException("Branch Manager and Staff need exactly one store; Owner and Admin none");
        }
        UserAccount account = new UserAccount();
        account.username = username;
        account.passwordHash = temporaryPasswordHash;
        account.fullName = fullName;
        account.role = role;
        account.storeId = storeId;
        account.status = AccountStatus.ACTIVE;
        account.mustChangePassword = true;
        account.createdBy = createdBy;
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    public void updateContactDetails(String newPhone, String newEmail, LocalDateTime now) {
        phone = newPhone;
        email = newEmail;
        updatedAt = now;
    }

    public boolean isLockedByAdmin() {
        return status == AccountStatus.LOCKED;
    }

    public boolean isTemporarilyLocked(LocalDateTime now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * Counts one more wrong password. On the {@code maxAttempts}-th in a row the account is locked
     * for {@code lockDuration} and the counter restarts (UC-01 step 5, SEC-02).
     *
     * @return true if this attempt locked the account
     */
    public boolean registerFailedLogin(LocalDateTime now, int maxAttempts, Duration lockDuration) {
        failedLoginCount++;
        updatedAt = now;
        if (failedLoginCount < maxAttempts) {
            return false;
        }
        failedLoginCount = 0;
        lockedUntil = now.plus(lockDuration);
        return true;
    }

    public void registerSuccessfulLogin(LocalDateTime now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
        updatedAt = now;
    }

    public void changePassword(String newPasswordHash, LocalDateTime now) {
        passwordHash = newPasswordHash;
        mustChangePassword = false;
        updatedAt = now;
    }

    /**
     * UC-11: Admin issues a new temporary password. The user must change it at the next sign-in,
     * and the temporary sign-in lock is lifted so they can sign in at once. An Admin lock stays.
     */
    public void resetPassword(String temporaryPasswordHash, LocalDateTime now) {
        passwordHash = temporaryPasswordHash;
        mustChangePassword = true;
        failedLoginCount = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    /** Usernames are stored in lower case, so "Staff01" and "staff01" are the same account. */
    public static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
