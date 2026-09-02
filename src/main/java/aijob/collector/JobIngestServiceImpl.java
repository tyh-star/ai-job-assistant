package aijob.collector;

import aijob.entity.JobIngestRun;
import aijob.entity.JobSource;
import aijob.entity.JobSourceRecord;
import aijob.enums.AtsProviderType;
import aijob.enums.IngestRunStatus;
import aijob.mapper.JobIngestRunMapper;
import aijob.mapper.JobSourceMapper;
import aijob.mapper.JobSourceRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class JobIngestServiceImpl implements JobIngestService {

    private static final Logger log = LoggerFactory.getLogger(JobIngestServiceImpl.class);
    private static final int EXPIRY_SCAN_LIMIT = 500;

    private final Map<AtsProviderType, JobBoardAdapter> adapters = new EnumMap<>(AtsProviderType.class);
    private final JobSourceMapper jobSourceMapper;
    private final JobSourceRecordMapper jobSourceRecordMapper;
    private final JobIngestRunMapper jobIngestRunMapper;
    private final JobIngestTransactions transactions;
    private final JobCollectorProperties properties;
    private final Clock clock;
    private final String owner;

    // Explicit: a second, package-private constructor exists for tests that need to
    // pin the owner id, so Spring cannot pick one by itself.
    @Autowired
    public JobIngestServiceImpl(List<JobBoardAdapter> boardAdapters,
                                JobSourceMapper jobSourceMapper,
                                JobSourceRecordMapper jobSourceRecordMapper,
                                JobIngestRunMapper jobIngestRunMapper,
                                JobIngestTransactions transactions,
                                JobCollectorProperties properties,
                                @Qualifier("jobSearchClock") Clock clock) {
        this(boardAdapters, jobSourceMapper, jobSourceRecordMapper, jobIngestRunMapper,
                transactions, properties, clock, CollectorOwner.forThisProcess());
    }

    JobIngestServiceImpl(List<JobBoardAdapter> boardAdapters,
                         JobSourceMapper jobSourceMapper,
                         JobSourceRecordMapper jobSourceRecordMapper,
                         JobIngestRunMapper jobIngestRunMapper,
                         JobIngestTransactions transactions,
                         JobCollectorProperties properties,
                         Clock clock,
                         String owner) {
        for (JobBoardAdapter adapter : boardAdapters) {
            this.adapters.put(adapter.providerType(), adapter);
        }
        this.jobSourceMapper = jobSourceMapper;
        this.jobSourceRecordMapper = jobSourceRecordMapper;
        this.jobIngestRunMapper = jobIngestRunMapper;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        this.owner = owner;
    }

    /**
     * Sources are grouped by provider so every board host is read strictly
     * sequentially, and only {@code maxConcurrency} hosts are read at once. This is
     * what keeps per-host load low no matter how many boards are configured.
     */
    @Override
    public List<JobIngestRun> collectDueSources() {
        LocalDateTime now = now();
        List<JobSource> due = jobSourceMapper.findDue(now, properties.getBatchSize());
        if (due.isEmpty()) {
            return List.of();
        }

        Map<String, List<JobSource>> byProvider = new LinkedHashMap<>();
        for (JobSource source : due) {
            byProvider.computeIfAbsent(
                    source.getProviderType() == null ? "" : source.getProviderType(),
                    key -> new ArrayList<>()).add(source);
        }

        if (byProvider.size() == 1 || properties.getMaxConcurrency() <= 1) {
            List<JobIngestRun> runs = new ArrayList<>(due.size());
            for (List<JobSource> group : byProvider.values()) {
                for (JobSource source : group) {
                    runs.add(collectSource(source));
                }
            }
            return runs;
        }

        int threads = Math.min(properties.getMaxConcurrency(), byProvider.size());
        List<JobIngestRun> runs = new ArrayList<>(due.size());
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<JobIngestRun>>> futures = new ArrayList<>(byProvider.size());
            for (List<JobSource> group : byProvider.values()) {
                futures.add(executor.submit(() -> {
                    List<JobIngestRun> groupRuns = new ArrayList<>(group.size());
                    for (JobSource source : group) {
                        groupRuns.add(collectSource(source));
                    }
                    return groupRuns;
                }));
            }
            for (Future<List<JobIngestRun>> future : futures) {
                try {
                    runs.addAll(future.get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException ex) {
                    log.warn("A collection group failed ({})",
                            ex.getCause() == null
                                    ? ex.getClass().getSimpleName()
                                    : ex.getCause().getClass().getSimpleName());
                }
            }
        } finally {
            executor.shutdown();
        }
        return runs;
    }

    /**
     * Collects one board.
     *
     * <p>The board is read <em>before</em> any transaction is opened, and each posting
     * is then written by {@link JobIngestTransactions} in its own short transaction. A
     * slow board therefore holds no database transaction and no row locks.
     */
    @Override
    public JobIngestRun collectSource(JobSource source) {
        LocalDateTime startedAt = now();

        if (!source.collectable()) {
            // Defence in depth: findDue already filters, but a direct call must not
            // collect a source the operator has not opted in for.
            return failRun(startRun(source, startedAt), source, "SOURCE_NOT_COLLECTABLE", startedAt);
        }

        // Cross-process lease. The scheduler's in-process guard cannot see a manual
        // `run --rm collector --aijob.collector.run-once=true` in a second container,
        // so the claim has to live in the database that both processes share.
        if (!acquireLease(source, startedAt)) {
            log.info("Source {} is already being collected elsewhere; skipping", source.getId());
            return skippedRun(source, startedAt);
        }

        try {
            return collectLockedSource(source, startedAt);
        } finally {
            // Released even when a posting blew up mid-run, so a crash inside the loop
            // cannot leave a board permanently claimed.
            releaseLease(source);
        }
    }

    private JobIngestRun collectLockedSource(JobSource source, LocalDateTime startedAt) {
        JobIngestRun run = startRun(source, startedAt);

        AtsProviderType providerType = parseProviderType(source.getProviderType());
        if (providerType == null) {
            return failRun(run, source, "UNSUPPORTED_PROVIDER_TYPE", startedAt);
        }
        JobBoardAdapter adapter = adapters.get(providerType);
        if (adapter == null) {
            return failRun(run, source, "NO_ADAPTER_REGISTERED", startedAt);
        }

        List<NormalizedJob> fetched;
        try {
            // Network I/O, deliberately outside every transaction.
            fetched = adapter.fetch(source);
        } catch (JobCollectionException ex) {
            return failRun(run, source, ex.reason(), startedAt);
        } catch (RuntimeException ex) {
            log.warn("Source {} failed with {}", source.getId(), ex.getClass().getSimpleName());
            return failRun(run, source, "UNEXPECTED_ADAPTER_ERROR", startedAt);
        }

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (NormalizedJob job : fetched) {
            try {
                IngestOutcome outcome = transactions.upsert(source, job, run.getId(), startedAt);
                switch (outcome) {
                    case INSERTED -> inserted++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                }
            } catch (RuntimeException ex) {
                failed++;
                // The posting's whole write was rolled back, so nothing partial was
                // left behind. Never log the payload: third-party content, unknown shape.
                log.warn("Skipped a posting from source {} ({})",
                        source.getId(), ex.getClass().getSimpleName());
            }
        }

        int expired = 0;
        if (failed == 0) {
            expired = expireMissing(source, startedAt);
        } else {
            log.warn("Source {} had {} unusable postings; skipping expiry this run",
                    source.getId(), failed);
        }

        run.setFinishedAt(now());
        run.setStatus(IngestRunStatus.SUCCESS.name());
        run.setFetchedCount(fetched.size());
        run.setInsertedCount(inserted);
        run.setUpdatedCount(updated);
        run.setUnchangedCount(unchanged);
        run.setExpiredCount(expired);
        run.setFailedCount(failed);
        run.setErrorSummary(null);
        jobIngestRunMapper.finish(run);

        jobSourceMapper.markSuccess(source.getId(), run.getFinishedAt(), nextRunAt(source));
        return run;
    }

    /**
     * Soft-expires postings this source stopped returning. Only ever called after a
     * complete, successful run, and only for records unseen for longer than the grace
     * period, so a board that briefly omits a job does not lose it. Each record is
     * retired in its own transaction by {@link JobIngestTransactions#expireRecord}.
     */
    int expireMissing(JobSource source, LocalDateTime runStartedAt) {
        LocalDateTime cutoff = runStartedAt.minusHours(properties.getExpiryGraceHours());
        List<JobSourceRecord> stale =
                jobSourceRecordMapper.findStale(source.getId(), cutoff, EXPIRY_SCAN_LIMIT);
        int expired = 0;
        for (JobSourceRecord record : stale) {
            expired += transactions.expireRecord(record.getId(), record.getCatalogJobId());
        }
        return expired;
    }

    @Override
    public void housekeeping() {
        LocalDateTime now = now();
        int runsDeleted = jobIngestRunMapper.deleteOlderThan(
                now.minusDays(properties.getRunHistoryRetentionDays()));
        int payloadsPurged = jobSourceRecordMapper.purgeRawPayloadsOlderThan(
                now.minusDays(properties.getRawPayloadRetentionDays()));
        if (runsDeleted > 0 || payloadsPurged > 0) {
            log.info("Collector housekeeping removed {} run rows and {} stored payloads",
                    runsDeleted, payloadsPurged);
        }
    }

    /**
     * Claims the source for this process. A lease older than
     * {@code lock-timeout-minutes} is treated as abandoned and taken over, so a killed
     * container cannot block a board forever.
     */
    private boolean acquireLease(JobSource source, LocalDateTime now) {
        LocalDateTime staleBefore = now.minusMinutes(properties.getLockTimeoutMinutes());
        return jobSourceMapper.tryLock(source.getId(), owner, now, staleBefore) == 1;
    }

    private void releaseLease(JobSource source) {
        try {
            // Owner-scoped: if our lease already expired and another process took over,
            // this releases nothing rather than freeing someone else's claim.
            jobSourceMapper.releaseLock(source.getId(), owner);
        } catch (RuntimeException ex) {
            log.warn("Could not release the collection lease for source {} ({})",
                    source.getId(), ex.getClass().getSimpleName());
        }
    }

    private JobIngestRun startRun(JobSource source, LocalDateTime startedAt) {
        JobIngestRun run = new JobIngestRun();
        run.setSourceId(source.getId());
        run.setStartedAt(startedAt);
        run.setStatus(IngestRunStatus.RUNNING.name());
        jobIngestRunMapper.insert(run);
        return run;
    }

    /**
     * A contended source is not a failure: nothing was wrong with the board, another
     * process is simply already reading it. Recording it as SKIPPED keeps it out of the
     * failure counters, so it never triggers the back-off.
     */
    private JobIngestRun skippedRun(JobSource source, LocalDateTime startedAt) {
        JobIngestRun run = startRun(source, startedAt);
        run.setFinishedAt(now());
        run.setStatus(IngestRunStatus.SKIPPED.name());
        run.setErrorSummary("SOURCE_LOCKED");
        jobIngestRunMapper.finish(run);
        return run;
    }

    private JobIngestRun failRun(JobIngestRun run,
                                 JobSource source,
                                 String reason,
                                 LocalDateTime startedAt) {
        run.setFinishedAt(now());
        run.setStatus(IngestRunStatus.FAILED.name());
        run.setErrorSummary(CollectorText.truncate(reason, 500));
        jobIngestRunMapper.finish(run);
        // A failed run expires nothing: an unreachable board is not evidence that its
        // postings are gone.
        jobSourceMapper.markFailure(source.getId(), backoffNextRunAt(source, startedAt));
        return run;
    }

    private LocalDateTime nextRunAt(JobSource source) {
        int interval = source.getCollectionIntervalMinutes() > 0
                ? source.getCollectionIntervalMinutes()
                : properties.getIntervalMinutes();
        return now().plusMinutes(interval);
    }

    /** Repeated failures back off, capped so a source is retried at least daily. */
    private LocalDateTime backoffNextRunAt(JobSource source, LocalDateTime startedAt) {
        int interval = source.getCollectionIntervalMinutes() > 0
                ? source.getCollectionIntervalMinutes()
                : properties.getIntervalMinutes();
        int failures = Math.min(source.getConsecutiveFailures() + 1, 5);
        long minutes = Math.min((long) interval * (1L << (failures - 1)), 24 * 60L);
        return startedAt.plusMinutes(minutes);
    }

    private static AtsProviderType parseProviderType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AtsProviderType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
