package aijob.collector.jsonl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The batch record written beside an exported JSONL file.
 *
 * <p>Its job is to make two lies impossible. A run that failed writes a manifest and no
 * JSONL, so a failure can never arrive as an empty-but-successful import; and a run that
 * was capped records {@code truncated}, so a sample can never be read as a complete
 * board. The importer refuses to write anything until the manifest and the file agree.
 *
 * <p>Unknown fields are ignored: the exporter may grow fields this importer does not
 * read yet, and that is not a reason to reject a batch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobExportManifest(
        @JsonProperty("run_id") String runId,
        String mode,
        String provider,
        String board,
        String company,
        @JsonProperty("fetched_count") Integer fetchedCount,
        @JsonProperty("exported_count") Integer exportedCount,
        Boolean truncated,
        @JsonProperty("complete_snapshot") Boolean completeSnapshot,
        String status,
        String error) {

    /** The exporter's two success values. Anything else is refused. */
    public static final String STATUS_OK = "ok";
    public static final String STATUS_OK_EMPTY = "ok_empty";

    public boolean succeeded() {
        return STATUS_OK.equals(status) || STATUS_OK_EMPTY.equals(status);
    }

    /**
     * Whether this batch is the whole board. Absent or false means it is a sample, and
     * the importer treats an absent value as "not complete" rather than assuming the
     * better case.
     */
    public boolean isCompleteSnapshot() {
        return Boolean.TRUE.equals(completeSnapshot) && !Boolean.TRUE.equals(truncated);
    }
}
