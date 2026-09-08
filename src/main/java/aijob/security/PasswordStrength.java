package aijob.security;

import aijob.exception.WeakPasswordException;

/**
 * Shared password-strength gate for registration and "change password". Login never
 * calls this: existing accounts keep using whatever they already set.
 *
 * <p>The raw string is validated as sent -- never {@code trim}'d -- so a password that
 * only becomes legal after stripping spaces is rejected. Whitespace and ISO control
 * characters are forbidden anywhere in the value.
 *
 * <p>This is the single implementation. DTOs must not duplicate a different regex.
 */
public final class PasswordStrength {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;

    private PasswordStrength() {
    }

    public static boolean isStrong(String password) {
        if (password == null) {
            return false;
        }
        int length = password.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean special = false;
        for (int i = 0; i < length; i++) {
            char c = password.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
            if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                special = true;
            }
        }
        return upper && lower && digit && special;
    }

    public static void requireStrong(String password) {
        if (!isStrong(password)) {
            throw new WeakPasswordException();
        }
    }
}
