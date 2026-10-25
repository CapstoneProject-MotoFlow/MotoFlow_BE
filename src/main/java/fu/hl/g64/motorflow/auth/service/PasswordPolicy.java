package fu.hl.g64.motorflow.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Rules for every password: the user's own (UC-02) and the temporary one the system issues (UC-08, UC-11).
 * 8 to 50 characters with a letter, a digit and a special character, and not the username (NFR-S05, SCR-02).
 * A failure is reported as MSG43 with MIN_LENGTH and MAX_LENGTH.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 50;
    /** BCrypt ignores everything after 72 bytes, so longer input is never accepted. */
    static final int MAX_BYTES = 72;
    static final int TEMPORARY_LENGTH = 12;
    /** No 0/O, 1/l/I or quotes: the password is read out or typed by hand. 64^12 = 2^72 combinations. */
    private static final String TEMPORARY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*?";
    private static final SecureRandom RANDOM = new SecureRandom();

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
                && password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c))
                && !password.equalsIgnoreCase(username);
    }

    /** A random password that passes {@link #isAcceptable}, for Admin to hand to the user once (UC-08, UC-11). */
    public static String randomTemporary(String username) {
        String password;
        do {
            StringBuilder chars = new StringBuilder(TEMPORARY_LENGTH);
            for (int i = 0; i < TEMPORARY_LENGTH; i++) {
                chars.append(TEMPORARY_ALPHABET.charAt(RANDOM.nextInt(TEMPORARY_ALPHABET.length())));
            }
            password = chars.toString();
        } while (!isAcceptable(password, username));
        return password;
    }
}
