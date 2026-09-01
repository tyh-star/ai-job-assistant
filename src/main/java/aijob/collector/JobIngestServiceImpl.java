package aijob.collector;

import aijob.entity.JobCatalogEntry;
import aijob.entity.JobIngestRun;
import aijob.entity.JobSource;
import aijob.entity.JobSourceRecord;
import aijob.enums.AtsProviderType;
import aijob.enums.CatalogJobStatus;
import aijob.enums.IngestRunStatus;
import aijob.mapper.JobCatalogMapper;
import aijob.mapper.JobIngestRunMapper;
import aijob.mapper.JobSourceMapper;
import aijob.mapper.JobSourceRecordMapper;
import aijob.util.CatalogFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private enum Outcome { INSERTED, UPDATED, UNCHANGED }

    private final Map<AtsProviderType, JobBoardAdapter> adapters = new EnumMap<>(AtsProviderType.class);
    private final JobSourceMapper jobSourceMapper;
    private final JobSourceRecordMapper jobSourceRecordMapper;
    private final JobCatalogMapper jobCatalogMapper;
    private final JobIngestRunMapper jobIngestRunMapper;
    private final JobCollectorProperties properties;
    private final Clock clock;

    public JobIngestServiceImpl(List<JobBoardAdapter> boardAdapters,
                                JobSourceMapper jobSourceMapper,
                                JobSourceRecordMapper jobSourceRecordMapper,
                                JobCatalogMapper jobCatalogMapper,
                                JobIngestRunMapper jobIngestRunMapper,
                                JobCollectorProperties properties,
                                @Qualifier("jobSearchClock") Clock clock) {
        for (JobBoardAdapter adapter : boardAdapters) {
            this.adapters.put(adapter.providerType(), adapter);
        }
        this.jobSourceMapper = jobSourceMapper;
        this.jobSourceRecordMapper = jobSourceRecordMapper;
        this.jobCatalogMapper = jobCatalogMapper;
        this.jobIngestRunMapper = jobIngestRunMapper;
        this.properties = properties;
        this.clock = clock;
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

    @Override
    public JobIngestRun collectSource(JobSource source) {
        LocalDateTime startedAt = now();
        JobIngestRun run = startRun(source, startedAt);

        if (!source.collectable()) {
            // Defence in depth: findDue already filters, but a direct call must not
            // collect a source the operator has not opted in for.
            return failRun(run, source, "SOURCE_NOT_COLLECTABLE", startedAt);
        }
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
                Outcome outcome = upsert(source, job, run.getId(), startedAt);
                switch (outcome) {
                    case INSERTED -> inserted++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                }
            } catch (RuntimeException ex) {
                failed++;
                // Never log the payload: it is third-party content of unknown shape.
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

    @Transactional
    Outcome upsert(JobSource source, NormalizedJob job, Long runId, LocalDateTime seenAt) {
        String contentHash = contentHash(job);
        JobSourceRecord record =
                jobSourceRecordMapper.findBySourceAndExternalId(source.getId(), job.externalJobId());

        if (record != null && contentHash.equals(record.getPayloadHash())) {
            jobSourceRecordMapper.touch(record.getId(), seenAt, runId);
            if (record.getCatalogJobId() != null) {
                JobCatalogEntry entry = jobCatalogMapper.findById(record.getCatalogJobId());
                if (entry != null) {
                    if (CatalogJobStatus.ACTIVE.name().equals(entry.getStatus())) {
                        jobCatalogMapper.touchLastSeen(entry.getId(), seenAt);
                    } else {
                        // The posting came back; un-expire rather than duplicate it.
                        jobCatalogMapper.restoreActive(entry.getId(), seenAt);
                    }
                }
            }
            return Outcome.UNCHANGED;
        }

        JobCatalogEntry target = null;
        if (record != null && record.getCatalogJobId() != null) {
            target = jobCatalogMapper.findById(record.getCatalogJobId());
        }
        if (target == null) {
            target = resolveExistingCatalogEntry(job);
        }

        if (target == null) {
            target = new JobCatalogEntry();
            target.setFirstSeenAt(seenAt);
            applyContent(target, job, seenAt);
            jobCatalogMapper.insert(target);
        } else {
            applyContent(target, job, seenAt);
            jobCatalogMapper.update(target);
        }

        if (record == null) {
            JobSourceRecord fresh = new JobSourceRecord();
            fresh.setSourceId(source.getId());
            fresh.setExternalJobId(job.externalJobId());
            fresh.setCatalogJobId(target.getId());
            fresh.setSourceUrl(job.canonicalUrl());
            fresh.setApplyUrl(job.applyUrl());
            fresh.setPayloadHash(contentHash);
            fresh.setRawPayload(job.rawPayload());
            fresh.setFirstSeenAt(seenAt);
            fresh.setLastSeenAt(seenAt);
            fresh.setLastSuccessfulRunId(runId);
            fresh.setActive(true);
            jobSourceRecordMapper.insert(fresh);
            return Outcome.INSERTED;
        }

        record.setCatalogJobId(target.getId());
        record.setSourceUrl(job.canonicalUrl());
        record.setApplyUrl(job.applyUrl());
        record.setPayloadHash(contentHash);
        record.setRawPayload(job.rawPayload());
        record.setLastSeenAt(seenAt);
        record.setLastSuccessfulRunId(runId);
        record.setActive(true);
        jobSourceRecordMapper.update(record);
        return Outcome.UPDATED;
    }

    /**
     * Cross-source de-duplication, in the order: normalized apply URL, then either
     * normalized URL column, then a company+title+location fingerprint.
     *
     * <p>The fingerprint step is skipped when the posting has no location, because
     * company+title alone routinely describes several genuinely different openings.
     * When in doubt this returns null and a second row is kept.
     */
    JobCatalogEntry resolveExistingCatalogEntry(NormalizedJob job) {
        String applyNorm = CatalogFingerprint.normalizeUrl(job.applyUrl());
        if (applyNorm != null) {
            JobCatalogEntry match = jobCatalogMapper.findByApplyUrlNorm(applyNorm);
            if (match != null) {
                return match;
            }
        }
        String canonicalNorm = CatalogFingerprint.normalizeUrl(job.canonicalUrl());
        if (canonicalNorm != null) {
            JobCatalogEntry match = jobCatalogMapper.findByAnyUrlNorm(canonicalNorm);
            if (match != null) {
                return match;
            }
        }
        if (job.location() != null && !job.location().isBlank()) {
            return jobCatalogMapper.findByFingerprint(
                    CatalogFingerprint.of(job.company(), job.title(), job.location()));
        }
        return null;
    }

    private void applyContent(JobCatalogEntry entry, NormalizedJob job, LocalDateTime seenAt) {
        entry.setTitle(job.title());
        entry.setCompanyName(job.company());
        entry.setDescriptionText(job.descriptionText());
        entry.setDescriptionHtml(job.descriptionHtml());
        entry.setLocationText(job.location());
        entry.setCity(LocationParser.city(job.location()));
        entry.setRegion(LocationParser.region(job.location()));
        entry.setCountryCode(job.country());
        entry.setRemoteType(job.remoteType().name());
        entry.setEmploymentType(job.employmentType().name());
        if (job.salary() != null) {
            entry.setSalaryMin(job.salary().min());
            entry.setSalaryMax(job.salary().max());
            entry.setCurrency(job.salary().currency());
        }
        entry.setCanonicalUrl(job.canonicalUrl());
        entry.setApplyUrl(job.applyUrl());
        entry.setCanonicalUrlNorm(CatalogFingerprint.normalizeUrl(job.canonicalUrl()));
        entry.setApplyUrlNorm(CatalogFingerprint.normalizeUrl(job.applyUrl()));
        entry.setPostedAt(job.postedAt());
        if (entry.getFirstSeenAt() == null) {
            entry.setFirstSeenAt(seenAt);
        }
        entry.setLastSeenAt(seenAt);
        entry.setStatus(CatalogJobStatus.ACTIVE.name());
        entry.setFingerprint(CatalogFingerprint.of(job.company(), job.title(), job.location()));
    }

    /**
     * Soft-expires postings this source stopped returning. Only ever called after a
     * complete, successful run, and only for records unseen for longer than the grace
     * period, so a board that briefly omits a job does not lose it.
     */
    @Transactional
    int expireMissing(JobSource source, LocalDateTime runStartedAt) {
        LocalDateTime cutoff = runStartedAt.minusHours(properties.getExpiryGraceHours());
        List<JobSourceRecord> stale =
                jobSourceRecordMapper.findStale(source.getId(), cutoff, EXPIRY_SCAN_LIMIT);
        int expired = 0;
        for (JobSourceRecord record : stale) {
            jobSourceRecordMapper.deactivate(record.getId());
            Long catalogJobId = record.getCatalogJobId();
            if (catalogJobId == null) {
                continue;
            }
            // Another board may still be publishing the same posting.
            if (jobSourceRecordMapper.countActiveForCatalogJob(catalogJobId) == 0) {
                expired += jobCatalogMapper.markExpired(catalogJobId);
            }
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

    private JobIngestRun startRun(JobSource source, LocalDateTime startedAt) {
        JobIngestRun run = new JobIngestRun();
        run.setSourceId(source.getId());
        run.setStartedAt(startedAt);
        run.setStatus(IngestRunStatus.RUNNING.name());
        jobIngestRunMapper.insert(run);
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

    /**
     * Hashes normalized content, not the raw payload: boards bump timestamps and
     * tracking fields on every fetch, and hashing those would report every posting as
     * changed on every run.
     */
    static String contentHash(NormalizedJob job) {
        String canonical = String.join("\u001f",
                CatalogFingerprint.normalizeText(job.title()),
                CatalogFingerprint.normalizeText(job.company()),
                CatalogFingerprint.normalizeText(job.location()),
                CatalogFingerprint.normalizeText(job.country()),
                job.remoteType().name(),
                job.employmentType().name(),
                String.valueOf(CatalogFingerprint.normalizeUrl(job.canonicalUrl())),
                String.valueOf(CatalogFingerprint.normalizeUrl(job.applyUrl())),
                String.valueOf(job.salary()),
                CatalogFingerprint.normalizeText(job.descriptionText()));
        return CatalogFingerprint.sha256(canonical);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
