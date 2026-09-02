package aijob.collector;

import aijob.enums.RemoteType;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Best-effort parsing of free-text ATS location strings.
 *
 * <p>Boards do not publish structured locations, so this only fills a field when the
 * text is unambiguous and leaves it null otherwise. A wrong country code would silently
 * hide postings from a filtered search, which is worse than an empty one.
 *
 * <p>This is the ONE place that turns location text into derived fields. The Go exporter
 * deliberately does not do it: upstream's contract is that {@code Job.Countries} carries
 * only what a platform states STRUCTURALLY, and adding text inference there would put a
 * guess in a field whose whole value is that it is not one. FreeHire has a much larger
 * location dictionary under {@code internal/dict/location}, but it is Go, under
 * {@code internal/}, and unreachable from a JVM — reusing it would mean copying its data
 * into a second copy that then drifts. So the rules live here, and only here.
 *
 * <h2>What the rules are based on</h2>
 * The 250 Greenhouse postings collected on 2026-09-02, read rather than assumed. In that
 * sample, countries are always spelled out — "United States" (19), "India" (8),
 * "Germany" (4), "England" (4), "United Kingdom" (3) — and never abbreviated, while every
 * two-letter trailing token was a US state beside a US city: NY, PA, VA, TX, IL. That is
 * what licenses reading a trailing two-letter token as a state rather than a country.
 */
public final class LocationParser {

    private static final Map<String, String> COUNTRY_CODES = Map.ofEntries(
            Map.entry("canada", "CA"),
            Map.entry("united states", "US"),
            Map.entry("united states of america", "US"),
            Map.entry("usa", "US"),
            Map.entry("us", "US"),
            Map.entry("u.s.", "US"),
            Map.entry("u.s.a.", "US"),
            Map.entry("united kingdom", "GB"),
            Map.entry("uk", "GB"),
            Map.entry("england", "GB"),
            Map.entry("scotland", "GB"),
            Map.entry("wales", "GB"),
            Map.entry("germany", "DE"),
            Map.entry("france", "FR"),
            Map.entry("spain", "ES"),
            Map.entry("italy", "IT"),
            Map.entry("netherlands", "NL"),
            Map.entry("belgium", "BE"),
            Map.entry("ireland", "IE"),
            Map.entry("portugal", "PT"),
            Map.entry("denmark", "DK"),
            Map.entry("norway", "NO"),
            Map.entry("finland", "FI"),
            Map.entry("austria", "AT"),
            Map.entry("australia", "AU"),
            Map.entry("new zealand", "NZ"),
            Map.entry("india", "IN"),
            Map.entry("singapore", "SG"),
            Map.entry("japan", "JP"),
            Map.entry("china", "CN"),
            Map.entry("south korea", "KR"),
            Map.entry("brazil", "BR"),
            Map.entry("mexico", "MX"),
            Map.entry("poland", "PL"),
            Map.entry("sweden", "SE"),
            Map.entry("switzerland", "CH"),
            Map.entry("israel", "IL"),
            Map.entry("united arab emirates", "AE"),
            Map.entry("uae", "AE"),
            // Added after reading what ten real boards actually publish: these appeared
            // as whole-string locations and had nowhere to resolve to.
            Map.entry("greece", "GR"),
            Map.entry("romania", "RO"),
            Map.entry("hungary", "HU"),
            Map.entry("czechia", "CZ"),
            Map.entry("czech republic", "CZ"),
            Map.entry("saudi arabia", "SA"),
            Map.entry("costa rica", "CR"),
            Map.entry("the netherlands", "NL"),
            Map.entry("holland", "NL"),
            Map.entry("taiwan", "TW"),
            Map.entry("vietnam", "VN"),
            Map.entry("turkey", "TR"),
            Map.entry("lithuania", "LT"),
            Map.entry("egypt", "EG"),
            Map.entry("south africa", "ZA"),
            Map.entry("argentina", "AR"),
            Map.entry("colombia", "CO"),
            Map.entry("chile", "CL"),
            Map.entry("philippines", "PH"),
            Map.entry("indonesia", "ID"),
            Map.entry("malaysia", "MY"),
            Map.entry("thailand", "TH")
    );

    /**
     * US state NAMES, spelled out. "Remote - California" and "Remote - Illinois" name a
     * place inside one country unambiguously, unlike the two-letter codes.
     *
     * <p>Georgia is deliberately absent: it is a US state AND a country (GE), and the
     * name alone does not say which — the same reasoning that keeps "CA" out of the code
     * set. Washington is included because both readings (the state and the district) are
     * in the US either way.
     */
    private static final Set<String> US_STATE_NAMES = Set.of(
            "alabama", "alaska", "arizona", "arkansas", "california", "colorado",
            "connecticut", "delaware", "florida", "hawaii", "idaho", "illinois",
            "indiana", "iowa", "kansas", "kentucky", "louisiana", "maine", "maryland",
            "massachusetts", "michigan", "minnesota", "mississippi", "missouri",
            "montana", "nebraska", "nevada", "new hampshire", "new jersey", "new mexico",
            "new york state", "north carolina", "north dakota", "ohio", "oklahoma",
            "oregon", "pennsylvania", "rhode island", "south carolina", "south dakota",
            "tennessee", "texas", "utah", "vermont", "virginia", "washington",
            "west virginia", "wisconsin", "wyoming");

    /**
     * US state postal codes. A trailing one of these means the posting is in the US.
     *
     * <p>"CA" is deliberately absent. It is both California and the ISO code for Canada,
     * and the code ALONE cannot separate them — the disambiguator is the city, which this
     * parser has no gazetteer for. A posting reading "Palo Alto, CA" therefore keeps an
     * unknown country. That is the intended trade: an empty filter result is recoverable,
     * a posting silently filed under the wrong country is not. A city gazetteer would
     * settle it, and is the honest fix rather than a guess here.
     *
     * <p>Spelled out, neither is ambiguous: "Toronto, ON, Canada" and "Remote Canada"
     * both resolve to CA, and "California, United States" to US.
     */
    private static final Set<String> US_STATE_CODES = Set.of(
            "al", "ak", "az", "ar", "co", "ct", "de", "dc", "fl", "ga", "hi", "id", "il",
            "in", "ia", "ks", "ky", "la", "me", "md", "ma", "mi", "mn", "ms", "mo", "mt",
            "ne", "nv", "nh", "nj", "nm", "ny", "nc", "nd", "oh", "ok", "or", "pa", "ri",
            "sc", "sd", "tn", "tx", "ut", "vt", "va", "wa", "wv", "wi", "wy");

    /**
     * Work-arrangement words that boards prefix onto a location: "Hybrid - London",
     * "Remote US", "In-Office". They describe HOW the job is done, not WHERE, and left in
     * place they end up inside the city — the sample had 6 postings whose city read
     * "Hybrid - San Francisco".
     */
    private static final Pattern WORK_MODE_PREFIX = Pattern.compile(
            "^\\s*(hybrid|remote|on-?site|in-?office|distributed|virtual|wfh)\\b\\s*[-–—:,]?\\s*",
            Pattern.CASE_INSENSITIVE);

    /** A trailing "(Open to US-based Remote)" and friends: commentary, not a place. */
    private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)");

    /** Separators a board might use between places, including ";" for a two-site posting. */
    private static final Pattern SEGMENTS = Pattern.compile("[,|/;]");

    private LocationParser() {
    }

    /**
     * Strips the work-arrangement prefix, repeatedly: "Remote - Hybrid - London" is not
     * common but costs nothing to handle, and one pass would leave "Hybrid - London".
     */
    static String stripWorkMode(String value) {
        if (value == null) {
            return null;
        }
        String out = value.strip();
        for (int i = 0; i < 3; i++) {
            String next = WORK_MODE_PREFIX.matcher(out).replaceFirst("").strip();
            if (next.equals(out)) {
                break;
            }
            out = next;
        }
        return out;
    }

    private static String withoutParentheticals(String value) {
        return PARENTHETICAL.matcher(value).replaceAll(" ").strip();
    }

    /**
     * @return an ISO-3166 alpha-2 code, or {@code null} when the text does not clearly
     *         name a country.
     */
    public static String countryCode(String locationText) {
        if (locationText == null || locationText.isBlank()) {
            return null;
        }
        String cleaned = withoutParentheticals(locationText);

        // The whole string first, for the "Remote Belgium" / "Remote - United States"
        // shape, where the country is not in its own comma-separated segment at all.
        String whole = stripWorkMode(cleaned).toLowerCase(Locale.ROOT);
        String direct = COUNTRY_CODES.get(whole);
        if (direct != null) {
            return direct;
        }
        if (US_STATE_NAMES.contains(whole)) {
            return "US";
        }

        // Then segment by segment, from the end, where a country normally sits.
        String[] parts = SEGMENTS.split(cleaned);
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = stripWorkMode(parts[i]).toLowerCase(Locale.ROOT);
            if (candidate.isEmpty()) {
                continue;
            }
            String code = COUNTRY_CODES.get(candidate);
            if (code != null) {
                return code;
            }
            // A trailing two-letter US state code. Only meaningful when something came
            // before it — a location that is nothing but "NY" names no country reliably.
            if (i > 0 && US_STATE_CODES.contains(candidate)) {
                return "US";
            }
            // A state spelled out is unambiguous wherever it sits, unlike a code.
            if (US_STATE_NAMES.contains(candidate)) {
                return "US";
            }
        }
        return null;
    }

    /**
     * The city-ish leading segment, when the text looks like "City, Region, Country".
     *
     * <p>Only the FIRST place is returned. "Hybrid - San Francisco, New York City, Austin"
     * yields San Francisco and loses the other two: the schema holds one city per posting,
     * and duplicating the posting per city would invent jobs that do not exist. Filtering
     * by a posting's second or third city therefore does not work — a real limitation, not
     * an oversight.
     */
    public static String city(String locationText) {
        if (locationText == null || locationText.isBlank()) {
            return null;
        }
        String cleaned = withoutParentheticals(locationText);
        String[] parts = SEGMENTS.split(cleaned);
        if (parts.length < 2) {
            return null;
        }
        String city = stripWorkMode(parts[0]);
        if (city.isEmpty()) {
            return null;
        }
        // "Remote, United States" leaves nothing but a country in the first segment; that
        // is not a city, and storing it as one would make a country name searchable as a
        // place it is not.
        if (COUNTRY_CODES.containsKey(city.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return truncate(city, 190);
    }

    public static String region(String locationText) {
        if (locationText == null || locationText.isBlank()) {
            return null;
        }
        String cleaned = withoutParentheticals(locationText);
        String[] parts = SEGMENTS.split(cleaned);
        if (parts.length < 3) {
            return null;
        }
        String region = stripWorkMode(parts[1]);
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
