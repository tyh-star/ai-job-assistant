package aijob.jobsearch;

import aijob.dto.JobSearchRequest;
import aijob.util.JobFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class JobSearchCacheKeys {

    private JobSearchCacheKeys() {
    }

    public static String queryKey(String provider, String country, JobSearchRequest request) {
        return provider
                + "|" + JobFingerprint.normalize(country)
                + "|" + JobFingerprint.normalize(request.keywords())
                + "|" + JobFingerprint.normalize(request.location())
                + "|" + (request.postedWithinDays() == null ? "all" : request.postedWithinDays())
                + "|" + request.page();
    }

    public static String nextPageKey(String provider, String country, JobSearchRequest request, String cursorHash) {
        return provider
                + "|" + JobFingerprint.normalize(country)
                + "|" + JobFingerprint.normalize(request.keywords())
                + "|" + JobFingerprint.normalize(request.location())
                + "|" + (request.postedWithinDays() == null ? "all" : request.postedWithinDays())
                + "|" + cursorHash;
    }

    public static String cursorHash(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(cursor.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            return "unavailable";
        }
    }
}
