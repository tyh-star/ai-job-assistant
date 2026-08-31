package aijob.util;

import java.util.Locale;

public final class JobFingerprint {

    private JobFingerprint() {
    }

    public static String of(String company, String title, String location, String sourceUrl) {
        return normalize(company) + "|" + normalize(title) + "|" + normalize(location) + "|" + normalizeUrl(sourceUrl);
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static String normalizeUrl(String value) {
        String normalized = normalize(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
