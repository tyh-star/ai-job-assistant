package aijob.collector.jsonl;

/**
 * Another process holds the source's lease, so this import did not run.
 *
 * <p>Deliberately distinct from {@link JobExportRejectedException}: the batch is fine and
 * would import cleanly on its own. Something else is writing this board right now — a
 * second pipeline run, or the crawler — and the right response is to come back later, not
 * to fix the file. The caller reports it as a skip rather than a failure.
 *
 * <p>Like a rejection, nothing was written.
 */
public class JobExportLockedException extends RuntimeException {

    public JobExportLockedException(String message) {
        super(message);
    }
}
