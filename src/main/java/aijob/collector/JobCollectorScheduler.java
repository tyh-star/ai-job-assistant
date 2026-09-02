package aijob.collector;

import aijob.mapper.JobSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background collection loop. Active only in the {@code collector} profile, which is
 * what the dedicated collector container runs; the API container never schedules
 * collection.
 */
@Component
@Profile("collector")
public class JobCollectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobCollectorScheduler.class);

    private final JobIngestService jobIngestService;
    private final JobSourceMapper jobSourceMapper;
    private final JobCollectorProperties properties;
    private final CollectorCommandMode commandMode;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public JobCollectorScheduler(JobIngestService jobIngestService,
                                 JobSourceMapper jobSourceMapper,
                                 JobCollectorProperties properties,
                                 CollectorCommandMode commandMode) {
        this.jobIngestService = jobIngestService;
        this.jobSourceMapper = jobSourceMapper;
        this.properties = properties;
        this.commandMode = commandMode;
        log.info("Collector container started (collection enabled={}, one-shot command={})",
                properties.isEnabled(), commandMode.isOneShotCommand());
    }

    @Scheduled(
            initialDelayString = "${aijob.collector.initial-delay-minutes:1}",
            fixedDelayString = "${aijob.collector.interval-minutes:120}",
            timeUnit = TimeUnit.MINUTES)
    public void collect() {
        // A one-shot command owns this process. The tick would otherwise fire underneath a
        // long import — same process, so the per-source lease cannot separate them — and
        // "import a sample" would quietly become "import a sample AND crawl the board".
        if (commandMode.isOneShotCommand()) {
            return;
        }
        if (!properties.isEnabled()) {
            return;
        }
        // Stops this container's next tick from overlapping its own slow run. It is a
        // process-local flag and nothing more: a second process -- an operator's
        // `run --rm collector`, or a replacement container during a deploy -- has its
        // own AtomicBoolean and would happily start collecting at the same time. The
        // guard that actually spans processes is the per-source lease taken in
        // JobIngestServiceImpl.collectSource against the shared database.
        if (!running.compareAndSet(false, true)) {
            log.info("Previous collection is still running; skipping this tick");
            return;
        }
        try {
            int runs = jobIngestService.collectDueSources().size();
            jobIngestService.housekeeping();
            log.info("Collection tick finished: {} source run(s)", runs);
        } catch (RuntimeException ex) {
            log.error("Collection tick failed ({})", ex.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    /**
     * Proves both the process and its database connection are alive, whether or not
     * collection is enabled, so a disabled collector still reports healthy/idle
     * instead of looking dead to the container healthcheck.
     */
    @Scheduled(initialDelay = 5, fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void heartbeat() {
        try {
            jobSourceMapper.findDue(java.time.LocalDateTime.of(1970, 1, 1, 0, 0), 1);
            writeHeartbeat();
        } catch (RuntimeException ex) {
            log.warn("Collector heartbeat failed ({})", ex.getClass().getSimpleName());
        }
    }

    private void writeHeartbeat() {
        Path path = Path.of(properties.getHeartbeatFile());
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, properties.isEnabled() ? "ENABLED\n" : "IDLE\n");
        } catch (IOException ex) {
            log.warn("Could not write the collector heartbeat file");
        }
    }
}
