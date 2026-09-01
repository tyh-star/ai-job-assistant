package aijob.collector;

import aijob.entity.JobIngestRun;
import aijob.entity.JobSource;

import java.util.List;

/**
 * Turns adapter output into {@code job_catalog} rows.
 *
 * <p>Runs entirely in the background. Nothing here is reachable from a user request,
 * which is what keeps user search on a pure local read.
 */
public interface JobIngestService {

    /**
     * Collects one source and records a {@code job_ingest_runs} row either way. A
     * source that could not be read is recorded as FAILED and expires nothing.
     */
    JobIngestRun collectSource(JobSource source);

    /** Collects every enabled, crawl-allowed source whose next run is due. */
    List<JobIngestRun> collectDueSources();

    /** Trims run history and stored payloads. Safe to call on every tick. */
    void housekeeping();
}
