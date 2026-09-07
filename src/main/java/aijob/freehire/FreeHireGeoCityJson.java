package aijob.freehire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One match from {@code GET /geo/cities}, confirmed live 2026-09-05:
 * {@code {"value":"Toronto","country":"ca"}} for {@code ?q=Toronto&country=ca}. Field
 * shape and names taken directly from the pinned upstream source
 * ({@code internal/api/handler/geo.go}'s {@code cityMatch}), not guessed: {@code value}
 * is the literal city name to send back as a {@code cities} search filter value (the
 * same string the job-search index's {@code cities} attribute carries, per
 * {@code internal/search/search/query_filter.go}'s {@code StringFacets["cities"]});
 * {@code country} is a lowercase ISO 3166-1 alpha-2 code, not a display label.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FreeHireGeoCityJson(String value, String country) {
}
