package aijob.collector;

import aijob.entity.JobIngestRun;
import aijob.entity.JobSource;
import aijob.enums.IngestRunStatus;
import aijob.mapper.JobSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single-shot collection for testing and operations:
 * {@code --aijob.collector.run-once=true} collects every due source and exits, and
 * {@code --aijob.collector.source-id=N} collects exactly one source.
 *
 * <p>Useful for a first controlled run on a new board before enabling the schedule.
 *
 * <p>Exit status is meaningful, so a deploy script can branch on it: 0 when every run
 * finished, non-zero when a board failed or the requested source does not exist. A
 * source that was skipped because another collector process held its lease is not a
 * failure.
 */
@Component
@Profile("collector")
public class JobCollectorRunner implements ApplicationRunner {

    public static final String RUN_ONCE_ARG = "aijob.collector.run-once";
    public static final String SOURCE_ID_ARG = "aijob.collector.source-id";

    private static final Logger log = LoggerFactory.getLogger(JobCollectorRunner.class);

    private final JobIngestService jobIngestService;
    private final JobSourceMapper jobSourceMapper;
    private final CollectorProcessExit processExit;

    public JobCollectorRunner(JobIngestService jobIngestService,
                              JobSourceMapper jobSourceMapper,
                              CollectorProcessExit processExit) {
        this.jobIngestService = jobIngestService;
        this.jobSourceMapper = jobSourceMapper;
        this.processExit = processExit;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long sourceId = singleLong(args, SOURCE_ID_ARG);
        if (sourceId != null) {
            JobSource source = jobSourceMapper.findById(sourceId);
            if (source == null) {
                log.error("No job source with id {}", sourceId);
                processExit.exit(2);
                return;
            }
            processExit.exit(report(List.of(jobIngestService.collectSource(source))));
            return;
        }
        if (!flag(args, RUN_ONCE_ARG)) {
            // Not a one-shot invocation: this is the scheduled collector starting up.
            return;
        }
        int status = report(jobIngestService.collectDueSources());
        jobIngestService.housekeeping();
        processExit.exit(status);
    }

    /** @return 0 when nothing failed, 1 when at least one source run failed. */
    private static int report(List<JobIngestRun> runs) {
        int failures = 0;
        for (JobIngestRun run : runs) {
            if (IngestRunStatus.FAILED.name().equals(run.getStatus())) {
                failures++;
            }
            log.info("Run {} source={} status={} fetched={} inserted={} updated={} "
                            + "unchanged={} expired={} failed={} error={}",
                    run.getId(), run.getSourceId(), run.getStatus(), run.getFetchedCount(),
                    run.getInsertedCount(), run.getUpdatedCount(), run.getUnchangedCount(),
                    run.getExpiredCount(), run.getFailedCount(), run.getErrorSummary());
        }
        log.info("Single-shot collection finished: {} run(s), {} failed", runs.size(), failures);
        return failures == 0 ? 0 : 1;
    }

    private static boolean flag(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {
            return false;
        }
        return values.isEmpty() || "true".equalsIgnoreCase(values.get(values.size() - 1));
    }

    private static Long singleLong(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(values.get(values.size() - 1).strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
