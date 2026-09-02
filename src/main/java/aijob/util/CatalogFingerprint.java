package aijob.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stable identity helpers for cross-source catalog de-duplication.
 *
 * <p>Everything here is derived from posting content, never from a database id, so
 * the same posting seen through two boards produces the same value.
 *
 * <p>Two different notions of "the same URL" live here on purpose:
 * <ul>
 *   <li>{@link #normalizeUrl(String)} is the mechanical form used for change
 *       detection. It answers "did this posting change?", so it tolerates a board
 *       rewriting its tracking parameters between fetches.</li>
 *   <li>{@link #identityUrl(String)} is the strict form used for de-duplication. It
 *       answers "is this provably the same opening?", and returns {@code null}
 *       whenever the URL is not specific enough to prove that. A careers landing page
 *       is not evidence, so it never becomes a merge key.</li>
 * </ul>
 */
public final class CatalogFingerprint {

    /**
     * Campaign/click identifiers only. Every one of these is documented as marketing
     * attribution by the party that defines it, and none of them ever carries a job
     * identifier. Anything not on this list is preserved: {@code ref} and
     * {@code source} in particular are used by real boards as part of a posting's
     * address, so treating them as noise would merge distinct openings.
     */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "utm_id", "utm_name", "utm_reader",
            "gh_src",
            "gclid", "dclid", "gbraid", "wbraid", "gad_source",
            "fbclid", "msclkid", "twclid", "ttclid", "igshid", "yclid", "li_fat_id",
            "mc_cid", "mc_eid", "_ga", "_gl", "_hsenc", "_hsmi");

    /**
     * Path segments that describe a section of a site rather than one opening. A URL
     * built only from these is a careers index, not a posting.
     */
    private static final Set<String> GENERIC_SEGMENTS = Set.of(
            "career", "careers", "job", "jobs", "joblist", "job-list",
            "position", "positions", "opening", "openings", "opportunity",
            "opportunities", "vacancy", "vacancies", "role", "roles",
            "join", "join-us", "joinus", "work-with-us", "workwithus", "hiring",
            "apply", "application", "applications", "search", "list", "all",
            "index", "home", "en", "en-us", "en-gb", "us", "uk", "www");

    /**
     * Query parameters that narrow a listing rather than name an opening. A careers
     * page plus one of these is still a careers page.
     */
    private static final Set<String> LISTING_PARAMS = Set.of(
            "lang", "language", "locale", "hl", "l",
            "page", "p", "per_page", "pagesize", "size", "limit", "offset", "start",
            "sort", "order", "dir", "view", "tab", "mode", "format",
            "q", "query", "search", "keyword", "keywords",
            "department", "departments", "team", "teams", "category", "categories",
            "location", "locations", "city", "country", "region", "remote",
            "type", "commitment", "workplacetype", "level", "filter", "filters");

    private CatalogFingerprint() {
    }

    /**
     * Company + title + location identity.
     *
     * <p>This is a <em>candidate</em> signal for finding suspected duplicates, not a
     * merge key. A company routinely posts several genuinely different openings under
     * one title in one city, each with its own requisition number, so ingestion never
     * merges on this value alone.
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
     * Mechanical URL normalization for change detection: no scheme, no leading
     * {@code www.}, no fragment, lower-cased host, no trailing slash, and known
     * campaign parameters removed. Every other query parameter is kept and sorted,
     * because a job identifier frequently lives in the query string
     * ({@code ?gh_jid=}, {@code ?token=}, {@code ?jobId=}). Path and query case is
     * preserved: some boards use case-sensitive tokens.
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
        url = url.replaceFirst("(?i)^https?://", "");

        String query = null;
        int questionMark = url.indexOf('?');
        if (questionMark >= 0) {
            query = url.substring(questionMark + 1);
            url = url.substring(0, questionMark);
        }

        url = url.replaceFirst("(?i)^www\\.", "");
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        int firstSlash = url.indexOf('/');
        String host = (firstSlash >= 0 ? url.substring(0, firstSlash) : url)
                .toLowerCase(Locale.ROOT);
        String path = firstSlash >= 0 ? url.substring(firstSlash) : "";

        String keptQuery = keepMeaningfulQuery(query);
        String normalized = host + path + (keptQuery.isEmpty() ? "" : "?" + keptQuery);
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * The strict de-duplication key: {@link #normalizeUrl(String)}, but only when the
     * URL actually identifies one opening.
     *
     * <p>A company home page, {@code /careers}, {@code /jobs}, or a shared application
     * form is the same URL for every posting on that board, so using it as a merge key
     * would collapse an entire board into one row. Those return {@code null} and the
     * caller keeps the postings apart.
     *
     * @return the merge key, or {@code null} when the URL is not strong evidence.
     */
    public static String identityUrl(String value) {
        String normalized = normalizeUrl(value);
        if (normalized == null || !carriesJobIdentity(normalized)) {
            return null;
        }
        return normalized;
    }

    /**
     * A normalized URL names one opening when it carries a query parameter that is not
     * merely a listing filter, or when its path goes at least two segments deep past
     * the host and is not built purely from section words.
     */
    static boolean carriesJobIdentity(String normalized) {
        String withoutQuery = normalized;
        String query = null;
        int questionMark = normalized.indexOf('?');
        if (questionMark >= 0) {
            withoutQuery = normalized.substring(0, questionMark);
            query = normalized.substring(questionMark + 1);
        }

        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.isBlank()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String name = (equals >= 0 ? pair.substring(0, equals) : pair)
                        .toLowerCase(Locale.ROOT);
                if (!LISTING_PARAMS.contains(name)) {
                    return true;
                }
            }
        }

        List<String> segments = pathSegments(withoutQuery);
        if (segments.size() < 2) {
            // One segment past the host is a tenant or section root, never a posting.
            return false;
        }
        for (String segment : segments) {
            if (!GENERIC_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> pathSegments(String hostAndPath) {
        int firstSlash = hostAndPath.indexOf('/');
        if (firstSlash < 0) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        for (String segment : hostAndPath.substring(firstSlash + 1).split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private static String keepMeaningfulQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        // Sorted so two boards that order the same parameters differently still match.
        TreeSet<String> kept = new TreeSet<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals >= 0 ? pair.substring(0, equals) : pair;
            if (TRACKING_PARAMS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            kept.add(pair);
        }
        return String.join("&", kept);
    }
}
