package aijob.freehire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One posting as FreeHire's hosted API (freehire.me/api/v1) serves it, from both
 * {@code GET /agent/jobs/search} and {@code GET /jobs/{slug}} — the two endpoints share
 * this shape (confirmed live 2026-09-03: identical key sets on both).
 *
 * <p>Only the fields this integration actually reads are declared; everything else
 * ({@code enrichment}, {@code reality}, the vote/view counters, {@code ghost}, ...) is
 * dropped by {@code @JsonIgnoreProperties(ignoreUnknown = true)} rather than mapped, since
 * nothing here uses them and a field this class does not declare cannot be a field this
 * class gets wrong.
 *
 * <p>{@code countries}/{@code regions} are FreeHire's own lowercase codes (confirmed
 * live: {@code ["co","us"]}, {@code ["latam","north_america"]}) — passed through
 * verbatim, never re-derived. {@code work_mode} is one of {@code remote}/{@code hybrid}/
 * {@code onsite}, or absent when the posting states none (confirmed live: the field is
 * missing from the JSON entirely on such a hit, not sent as {@code null} or {@code ""}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FreeHireJobJson(
        @JsonProperty("public_slug") String publicSlug,
        String source,
        @JsonProperty("external_id") String externalId,
        // The posting's own apply/original-listing link — NOT a freehire.me URL.
        // Confirmed live: FreeHire appends its own ?utm_source=freehire.me to the
        // employer's real link rather than proxying it, so this is still a link to the
        // employer's (or their ATS's) own site.
        String url,
        String title,
        String company,
        @JsonProperty("company_slug") String companySlug,
        // Free-text location as the source published it; never reparsed here.
        String location,
        // Full HTML description. The agent-search endpoint always includes this in full
        // (it exists specifically so a programmatic caller does not need a second
        // request); the plain /jobs/search endpoint would truncate it, but this
        // integration never calls that one.
        String description,
        List<String> countries,
        List<String> regions,
        @JsonProperty("work_mode") String workMode,
        List<String> cities,
        List<String> skills,
        @JsonProperty("is_tech") String isTech,
        @JsonProperty("posted_at") String postedAt,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("closed_at") String closedAt
) {
}
