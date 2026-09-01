package aijob.collector;

import aijob.enums.RemoteType;

import java.util.Locale;
import java.util.Map;

/**
 * Best-effort parsing of free-text ATS location strings.
 *
 * <p>Boards do not publish structured locations, so this only fills a field when the
 * text is unambiguous and leaves it null otherwise. A wrong country code would
 * silently hide postings from a filtered search, which is worse than an empty one.
 */
public final class LocationParser {

    private static final Map<String, String> COUNTRY_CODES = Map.ofEntries(
            Map.entry("canada", "CA"),
            Map.entry("ca", "CA"),
            Map.entry("united states", "US"),
            Map.entry("united states of america", "US"),
            Map.entry("usa", "US"),
            Map.entry("us", "US"),
            Map.entry("u.s.", "US"),
            Map.entry("u.s.a.", "US"),
            Map.entry("united kingdom", "GB"),
            Map.entry("uk", "GB"),
            Map.entry("england", "GB"),
            Map.entry("germany", "DE"),
            Map.entry("france", "FR"),
            Map.entry("spain", "ES"),
            Map.entry("italy", "IT"),
            Map.entry("netherlands", "NL"),
            Map.entry("ireland", "IE"),
            Map.entry("australia", "AU"),
            Map.entry("new zealand", "NZ"),
            Map.entry("india", "IN"),
            Map.entry("singapore", "SG"),
            Map.entry("japan", "JP"),
            Map.entry("china", "CN"),
            Map.entry("brazil", "BR"),
            Map.entry("mexico", "MX"),
            Map.entry("poland", "PL"),
            Map.entry("sweden", "SE"),
            Map.entry("switzerland", "CH"),
            Map.entry("israel", "IL")
    );

    private LocationParser() {
    }

    /**
     * @return an ISO-3166 alpha-2 code, or {@code null} when the text does not clearly
     *         name a country.
     */
    public static String countryCode(String locationText) {
        if (locationText == null || locationText.isBlank()) {
            return null;
        }
        String[] parts = locationText.split("[,|/]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].strip().toLowerCase(Locale.ROOT);
            if (candidate.isEmpty()) {
                continue;
            }
            String code = COUNTRY_CODES.get(candidate);
            if (code != null) {
                return code;
            }
        }
        return null;
    }

    /** The city-ish leading segment, when the text looks like "City, Region, Country". */
    public static String city(String locationText) {
        if (locationText == null || locationText.isBlank()) {
            return null;
        }
        String[] parts = locationText.split("[,|]");
        if (parts.length < 2) {
            return null;
        }
        String city = parts[0].strip();
        return city.isEmpty() ? null : truncate(city, 190);
    }

    public static String region(String locationText) {
        if (locationText == null || locationText.isBlank()) {
            return null;
        }
        String[] parts = locationText.split("[,|]");
        if (parts.length < 3) {
            return null;
        }
        String region = parts[1].strip();
        return region.isEmpty() ? null : truncate(region, 190);
    }

    /**
     * Only used by boards that do not state a workplace type. Reads "remote" and
     * "hybrid" out of the location text and reports UNSPECIFIED for anything else,
     * rather than guessing ONSITE.
     */
    public static RemoteType remoteTypeFromText(String locationText) {
        if (locationText == null || locationText.isBlank()) {
            return RemoteType.UNSPECIFIED;
        }
        String text = locationText.toLowerCase(Locale.ROOT);
        if (text.contains("hybrid")) {
            return RemoteType.HYBRID;
        }
        if (text.contains("remote") || text.contains("anywhere") || text.contains("distributed")) {
            return RemoteType.REMOTE;
        }
        return RemoteType.UNSPECIFIED;
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() <= max) {
            return stripped;
        }
        return stripped.substring(0, max);
    }
}
