package aijob.collector.jsonl;

/**
 * A batch that was refused before anything was written.
 *
 * <p>Every rejection this carries is raised during validation, ahead of the first
 * database write, so a refused batch leaves the catalog exactly as it was — no partial
 * import, no half-updated postings, and nothing to undo.
 */
public class JobExportRejectedException extends RuntimeException {

    public JobExportRejectedException(String message) {
        super(message);
    }
}
