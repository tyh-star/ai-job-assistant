package aijob.enums;

public enum IngestRunStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    /**
     * Another collector process already held this source's lease, so this attempt did
     * nothing. Deliberately distinct from FAILED: the board was fine, and a skipped
     * attempt must not count towards the failure back-off.
     */
    SKIPPED,
    /**
     * An offline import of an exported sample: postings were inserted or updated, but
     * the batch was NOT a complete reading of the board.
     *
     * <p>Deliberately not SUCCESS. SUCCESS is what licenses the expiry sweep to treat
     * everything it did not see as gone, and a sample that carried 50 of a board's 89
     * postings would close the other 39. This status says "these rows are current" and
     * nothing at all about the rows it did not mention.
     */
    SAMPLE_IMPORT
}
