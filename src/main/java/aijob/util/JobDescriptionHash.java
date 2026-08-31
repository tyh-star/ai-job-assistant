package aijob.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical JD hashing for Job Requirement Profile cache keys.
 * Newlines are unified and the text is trimmed; case is preserved.
 */
public final class JobDescriptionHash {

    private JobDescriptionHash() {
    }

    public static String canonicalize(String description) {
        if (description == null) {
            return "";
        }
        return description.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    public static String sha256(String description) {
        String canonical = canonicalize(description);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
