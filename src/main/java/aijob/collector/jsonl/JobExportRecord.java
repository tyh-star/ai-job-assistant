package aijob.collector.jsonl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One posting as the exporter wrote it.
 *
 * <p>A note on {@code postedAt}, because the field name is inherited and misleading:
 * the exporter fills it from the upstream adapter's {@code Job.PostedAt}, and what that
 * carries is provider-dependent. Lever's is the posting's {@code createdAt}; Greenhouse
 * has no publication date in its board API at all, so its adapter fills the same field
 * from {@code updated_at} — a last-modified stamp. {@link JobExportImporter} decides per
 * provider whether the value may become a publication date, and stores no date rather
 * than a wrong one. The raw line is kept regardless, so nothing is lost.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobExportRecord(
        @JsonProperty("run_id") String runId,
        String provider,
        String board,
        @JsonProperty("external_id") String externalId,
        String title,
        String company,
        String location,
        @JsonProperty("apply_url") String applyUrl,
        String description,
        Boolean remote,
        @JsonProperty("posted_at") String postedAt,
        @JsonProperty("work_mode") String workMode,
        @JsonProperty("employment_type") String employmentType,
        List<String> countries,
        @JsonProperty("fetched_at") String fetchedAt) {

    /** The identity a posting is stored under: provider + board + external id. */
    public String identity() {
        return provider + "/" + board + "/" + externalId;
    }
}
