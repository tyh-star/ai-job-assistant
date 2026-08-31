package aijob.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable Tailor Analysis requirement IDs. IDs are derived from content, not
 * from the order OpenAI returned requirements.
 */
public final class RequirementProfileIds {

    public static final int SCHEMA_VERSION = 4;

    private RequirementProfileIds() {
    }

    public static String stableId(String category, String priority, String requirement) {
        String raw = EvidenceText.normalize(empty(category))
                + "|"
                + EvidenceText.normalize(empty(priority))
                + "|"
                + EvidenceText.normalize(empty(requirement));
        return "req-" + sha256(raw).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
