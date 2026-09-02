package aijob.collector.jsonl;

/**
 * What one import actually did.
 *
 * <p>{@code completeSnapshot} is carried through from the manifest rather than derived
 * from the import going well: a batch of 50 postings can import perfectly and still be
 * 50 of a board's 89.
 */
public record JobExportImportSummary(
        String runId,
        String provider,
        String board,
        Long sourceId,
        Long ingestRunId,
        int recordsRead,
        int inserted,
        int updated,
        int unchanged,
        int failed,
        boolean completeSnapshot) {

    /**
     * Always 0. Expiry is not part of an import and there is no code path here that can
     * close a posting — the field exists so a caller reporting on a run can state that
     * as a fact it read back, rather than as an absence it assumed.
     */
    public int expired() {
        return 0;
    }
}
