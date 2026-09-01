package aijob.collector;

import aijob.entity.JobIngestRun;
import aijob.entity.JobSource;
import aijob.mapper.JobSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single-shot collection for testing and operations:
 * {@code --aijob.collector.run-once=true} collects every due source and exits, and
 * {@code --aijob.collector.source-id=N} collects exactly one source.
 *
 * <p>Useful for a first controlled run on a new board before enabling the schedule.
 */
@Component
@Profile("collector")
public class JobCollectorRunner implements ApplicationRunner {

    public static final String RUN_ONCE_ARG = "aijob.collector.run-once";
    public static final String SOURCE_ID_ARG = "aijob.collector.source-id";

    private static final Logger log = LoggerFactory.getLogger(JobCollectorRunner.class);

    private final JobIngestService jobIngestService;
    private final JobSourceMapper jobSourceMapper;
    private final ConfigurableApplicationContext context;

    public JobCollectorRunner(JobIngestService jobIngestService,
                              JobSourceMapper jobSourceMapper,
                              ConfigurableApplicationContext context) {
        this.jobIngestService = jobIngestService;
        this.jobSourceMapper = jobSourceMapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long sourceId = singleLong(args, SOURCE_ID_ARG);
        if (sourceId != null) {
            JobSource source = jobSourceMapper.findById(sourceId);
            if (source == null) {
                log.error("No job source with id {}", sourceId);
            } else {
                report(List.of(jobIngestService.collectSource(source)));
            }
            shutDown();
            return;
        }
        if (!flag(args, RUN_ONCE_ARG)) {
            return;
        }
        report(jobIngestService.collectDueSources());
        jobIngestService.housekeeping();
        shutDown();
    }

    /**
     * The scheduler keeps non-daemon threads alive, so a single-shot run has to close
     * the context explicitly or the container would never exit.
     */
    private void shutDown() {
        SpringApplication.exit(context, () -> 0);
    }

    private static void report(List<JobIngestRun> runs) {
        for (JobIngestRun run : runs) {
            log.info("Run {} source={} status={} fetched={} inserted={} updated={} "
                            + "unchanged={} expired={} failed={} error={}",
                    run.getId(), run.getSourceId(), run.getStatus(), run.getFetchedCount(),
                    run.getInsertedCount(), run.getUpdatedCount(), run.getUnchangedCount(),
                    run.getExpiredCount(), run.getFailedCount(), run.getErrorSummary());
        }
        log.info("Single-shot collection finished: {} run(s)", runs.size());
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
