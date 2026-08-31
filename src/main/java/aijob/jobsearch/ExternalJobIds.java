package aijob.jobsearch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ExternalJobIds {

    private ExternalJobIds() {
    }

    public static String persistId(String source, String rawJobId) {
        if (source == null || rawJobId == null) {
            throw new IllegalArgumentException("source and rawJobId are required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((source + ":" + rawJobId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
