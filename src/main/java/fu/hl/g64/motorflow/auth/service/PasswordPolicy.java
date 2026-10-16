package fu.hl.g64.motorflow.auth.service;

import java.nio.charset.StandardCharsets;

/**
 * Rules for every password a person chooses: the user's own (UC-02) and the temporary one
 * Admin issues (UC-08, UC-11). A failure is reported as MSG43 with MIN_LENGTH and MAX_LENGTH.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    /** BCrypt ignores everything after 72 bytes, so longer input is never accepted. */
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    public static boolean isAcceptable(String password, String username) {
        return password.length() >= MIN_LENGTH
                && password.length() <= MAX_LENGTH
                && fitsBcrypt(password)
                && password.chars().anyMatch(Character::isLetter)
                && password.chars().anyMatch(Character::isDigit)
                && !password.equalsIgnoreCase(username);
    }
}
