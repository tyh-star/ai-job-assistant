package aijob.service;

import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.RecordJobSearchHistoryRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Canonical form of a catalog search used for {@code criteria_hash}. Page number is
 * never part of the hash -- pagination must not create a new history row. Empty and
 * default-sort-only searches are not persisted.
 *
 * <p>City is a structured facet that is replayed into the search form, but it is still
 * case-folded with {@link Locale#ROOT} (same as keywords). {@code Toronto} and
 * {@code toronto} are the same search. Country codes and enums are upper-cased.
 */
public final class JobSearchHistoryCriteria {

    public record Normalized(
            String keywords,
            String city,
            String cityCountry,
            String country,
            String workMode,
            String employmentType,
            Integer postedWithin,
            String sortBy,
            String criteriaHash
    ) {
        public boolean isEmpty() {
            return isBlank(keywords)
                    && isBlank(city)
                    && isBlank(cityCountry)
                    && isBlank(country)
                    && isBlank(workMode)
                    && isBlank(employmentType)
                    && postedWithin == null
                    && (isBlank(sortBy) || JobCatalogSearchRequest.SORT_RELEVANCE.equals(sortBy));
        }
    }

    private JobSearchHistoryCriteria() {
    }

    public static Normalized normalize(RecordJobSearchHistoryRequest request) {
        if (request == null) {
            return empty();
        }
        String keywords = collapse(request.keywords());
        if (keywords != null) {
            keywords = keywords.toLowerCase(Locale.ROOT);
        }
        String city = foldCity(request.city());
        String cityCountry = upperCode(request.cityCountry());
        String country = upperCode(request.country());
        String workMode = upperToken(request.remoteType());
        String employmentType = upperToken(request.employmentType());
        Integer postedWithin = request.postedWithinDays();
        String sortBy = upperToken(request.sort());
        if (JobCatalogSearchRequest.SORT_RELEVANCE.equals(sortBy)) {
            sortBy = null;
        }
        String hash = sha256(join(
                nullToEmpty(keywords),
                nullToEmpty(city),
                nullToEmpty(cityCountry),
                nullToEmpty(country),
                nullToEmpty(workMode),
                nullToEmpty(employmentType),
                postedWithin == null ? "" : postedWithin.toString(),
                nullToEmpty(sortBy)));
        return new Normalized(
                keywords, city, cityCountry, country, workMode, employmentType, postedWithin, sortBy, hash);
    }

    private static Normalized empty() {
        return new Normalized(null, null, null, null, null, null, null, null, sha256(""));
    }

    private static String collapse(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        return trimmed.replaceAll("\\s+", " ");
    }

    private static String foldCity(String raw) {
        String trimmed = trimToNull(raw);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String upperCode(String raw) {
        String trimmed = trimToNull(raw);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String upperToken(String raw) {
        String trimmed = trimToNull(raw);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String join(String... parts) {
        return String.join("\n", parts);
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
