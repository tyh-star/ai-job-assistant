package aijob.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Stable identity helpers for cross-source catalog de-duplication.
 *
 * <p>Everything here is derived from posting content, never from a database id, so
 * the same posting seen through two boards produces the same value.
 */
public final class CatalogFingerprint {

    private CatalogFingerprint() {
    }

    /**
     * Company + title + location identity. Deliberately includes company and
     * location: title alone is far too weak to merge two postings on.
     */
    public static String of(String company, String title, String location) {
        String canonical = String.join("|",
                normalizeText(company),
                normalizeText(title),
                normalizeText(location));
        return sha256(canonical);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    public static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Normalizes a posting URL for equality checks: lower-cased host, no scheme
     * difference, no trailing slash, and no tracking query string.
     *
     * @return {@code null} when there is nothing comparable.
     */
    public static String normalizeUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String url = value.strip();
        int fragment = url.indexOf('#');
        if (fragment >= 0) {
            url = url.substring(0, fragment);
        }
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        url = url.replaceFirst("(?i)^https?://", "");
        url = url.replaceFirst("(?i)^www\\.", "");
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        url = url.toLowerCase(Locale.ROOT);
        return url.isBlank() ? null : url;
    }
}
