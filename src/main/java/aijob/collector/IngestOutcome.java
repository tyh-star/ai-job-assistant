package aijob.collector;

/** What one posting did to the catalog during a collection run. */
public enum IngestOutcome {
    INSERTED,
    UPDATED,
    UNCHANGED
}
