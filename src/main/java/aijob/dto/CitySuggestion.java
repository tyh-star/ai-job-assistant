package aijob.dto;

/**
 * One city a caller may filter by -- {@code value} is opaque and must be sent back
 * verbatim as the {@code city} search param; never reformatted, cased, or parsed. This
 * decouples the wire shape from FreeHire's own {@code GET /geo/cities} response
 * ({@code aijob.freehire.FreeHireGeoCityJson}), which this type is mapped from 1:1 but
 * does not expose directly to callers of {@link aijob.service.JobCatalogService}.
 */
public record CitySuggestion(String value, String countryCode) {
}
