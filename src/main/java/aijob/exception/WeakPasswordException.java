package aijob.exception;

/**
 * Registration or a new-password write failed the shared {@code PasswordStrength} gate.
 * Stable API code is {@link #CODE} -- never a generic 500.
 */
public class WeakPasswordException extends RuntimeException {

    public static final String CODE = "WEAK_PASSWORD";

    public WeakPasswordException() {
        super("Password does not meet strength requirements");
    }
}
