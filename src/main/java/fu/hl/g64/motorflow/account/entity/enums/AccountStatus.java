package fu.hl.g64.motorflow.account.entity.enums;

/**
 * LOCKED means locked by Admin (UC-10). The temporary lock after failed sign-ins is a separate
 * field ({@code locked_until}) so that its expiry never lifts an Admin lock (NFR-S06).
 */
public enum AccountStatus {
    ACTIVE,
    LOCKED
}
