package aijob.collector;

import aijob.entity.JobCatalogEntry;
import aijob.entity.JobSource;
import aijob.entity.JobSourceRecord;
import aijob.enums.CatalogJobStatus;
import aijob.mapper.JobCatalogMapper;
import aijob.mapper.JobSourceRecordMapper;
import aijob.util.CatalogFingerprint;
import aijob.util.ExternalLinks;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The database half of ingestion, isolated so its transactions are real.
 *
 * <p>This class exists because of a Spring rule that is easy to get wrong: the
 * transaction interceptor lives in a proxy, so a {@code @Transactional} method called
 * from inside the same object is not intercepted at all (and Spring additionally
 * ignores the annotation on non-public methods). Keeping these methods on a separate
 * bean means {@link JobIngestServiceImpl} reaches them through the proxy and the
 * boundaries below are enforced instead of merely documented.
 *
 * <p>Nothing here performs network I/O. Board reads happen in
 * {@link JobIngestServiceImpl#collectSource} before any transaction is opened, so a
 * slow or hung board can never hold a database transaction open.
 */
@Component
public class JobIngestTransactions {

    private final JobSourceRecordMapper jobSourceRecordMapper;
    private final JobCatalogMapper jobCatalogMapper;

    public JobIngestTransactions(JobSourceRecordMapper jobSourceRecordMapper,
                                 JobCatalogMapper jobCatalogMapper) {
        this.jobSourceRecordMapper = jobSourceRecordMapper;
        this.jobCatalogMapper = jobCatalogMapper;
    }

    /**
     * Writes one posting as a single unit: the catalog row, the source record that
     * points at it, and the catalog's effective links either all land or none do.
     *
     * <p>{@code REQUIRES_NEW} keeps one unusable posting from poisoning the postings
     * around it: the caller catches the failure, counts it, and moves on with the rest
     * of the run already committed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IngestOutcome upsert(JobSource source,
                                NormalizedJob job,
                                Long runId,
                                LocalDateTime seenAt) {
        String contentHash = contentHash(job);
        JobSourceRecord record =
                jobSourceRecordMapper.findBySourceAndExternalId(source.getId(), job.externalJobId());

        if (record != null && contentHash.equals(record.getPayloadHash())) {
            jobSourceRecordMapper.touch(record.getId(), seenAt, runId);
            Long catalogJobId = record.getCatalogJobId();
            if (catalogJobId != null) {
                JobCatalogEntry entry = jobCatalogMapper.findById(catalogJobId);
                if (entry != null) {
                    if (CatalogJobStatus.ACTIVE.name().equals(entry.getStatus())) {
                        jobCatalogMapper.touchLastSeen(entry.getId(), seenAt);
                    } else {
                        // The posting came back; un-expire rather than duplicate it.
                        jobCatalogMapper.restoreActive(entry.getId(), seenAt);
                    }
                    // This source became active again, which can change which record
                    // supplies the catalog's links.
                    refreshEffectiveLinks(catalogJobId);
                }
            }
            return IngestOutcome.UNCHANGED;
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

        IngestOutcome outcome = writeSourceRecord(source, job, record, target.getId(), contentHash, runId, seenAt);
        refreshEffectiveLinks(target.getId());
        return outcome;
    }

    /**
     * Retires one source record that a successful run stopped returning, and expires
     * the catalog row only when no other board still publishes it. Deactivating the
     * record and re-picking the catalog's links happen together, so the catalog never
     * keeps pointing at the apply link of a source that just went away.
     *
     * @return the number of catalog rows this moved to EXPIRED (0 or 1).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireRecord(Long recordId, Long catalogJobId) {
        jobSourceRecordMapper.deactivate(recordId);
        if (catalogJobId == null) {
            return 0;
        }
        int expired = 0;
        // Another board may still be publishing the same posting.
        if (jobSourceRecordMapper.countActiveForCatalogJob(catalogJobId) == 0) {
            expired = jobCatalogMapper.markExpired(catalogJobId);
        }
        refreshEffectiveLinks(catalogJobId);
        return expired;
    }

    /**
     * Cross-source de-duplication. Two postings are merged only on a URL that provably
     * names one opening; see {@link CatalogFingerprint#identityUrl(String)}.
     *
     * <p>There is deliberately no company+title+location branch. A company routinely
     * runs several different openings with the same title in the same city under
     * separate requisition numbers, and merging those loses a real job. The
     * {@code fingerprint} column is still written so an operator can list suspected
     * duplicates, but ingestion never acts on it: when there is no strong evidence
     * this returns {@code null} and a second row is kept.
     */
    JobCatalogEntry resolveExistingCatalogEntry(NormalizedJob job) {
        String applyIdentity = CatalogFingerprint.identityUrl(job.applyUrl());
        if (applyIdentity != null) {
            // Matched against either column because one board's canonical link is
            // another board's apply link.
            JobCatalogEntry match = jobCatalogMapper.findByAnyUrlNorm(applyIdentity);
            if (match != null) {
                return match;
            }
        }
        String canonicalIdentity = CatalogFingerprint.identityUrl(job.canonicalUrl());
        if (canonicalIdentity != null && !canonicalIdentity.equals(applyIdentity)) {
            JobCatalogEntry match = jobCatalogMapper.findByAnyUrlNorm(canonicalIdentity);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    /**
     * Re-derives the catalog's displayed links from the source records instead of
     * letting whichever board was collected last overwrite them.
     *
     * <p>The rule is: prefer a still-active record, and among equals prefer the one
     * seen first (lowest id). "Active first" stops the catalog from advertising an
     * apply link whose board has dropped the posting; "lowest id" makes the choice
     * stable, so two boards collected in either order settle on the same link.
     *
     * <p>Every board's own links stay on its {@code job_source_records} row, so
     * nothing is lost by not showing it.
     */
    private void refreshEffectiveLinks(Long catalogJobId) {
        JobSourceRecord preferred = jobSourceRecordMapper.findPreferredForCatalogJob(catalogJobId);
        if (preferred == null) {
            return;
        }
        String canonicalUrl = preferred.getSourceUrl();
        String applyUrl = preferred.getApplyUrl();
        jobCatalogMapper.updateEffectiveLinks(
                catalogJobId,
                canonicalUrl,
                applyUrl,
                CatalogFingerprint.identityUrl(canonicalUrl),
                CatalogFingerprint.identityUrl(applyUrl));
    }

    private IngestOutcome writeSourceRecord(JobSource source,
                                            NormalizedJob job,
                                            JobSourceRecord existing,
                                            Long catalogJobId,
                                            String contentHash,
                                            Long runId,
                                            LocalDateTime seenAt) {
        // Stored links are display links: any host is legitimate, but the scheme is
        // checked so nothing that could execute reaches an href later.
        String canonicalUrl = ExternalLinks.displayUrl(job.canonicalUrl());
        String applyUrl = ExternalLinks.displayUrl(job.applyUrl());

        if (existing == null) {
            JobSourceRecord fresh = new JobSourceRecord();
            fresh.setSourceId(source.getId());
            fresh.setExternalJobId(job.externalJobId());
            fresh.setCatalogJobId(catalogJobId);
            fresh.setSourceUrl(canonicalUrl);
            fresh.setApplyUrl(applyUrl);
            fresh.setPayloadHash(contentHash);
            fresh.setRawPayload(job.rawPayload());
            fresh.setFirstSeenAt(seenAt);
            fresh.setLastSeenAt(seenAt);
            fresh.setLastSuccessfulRunId(runId);
            fresh.setActive(true);
            jobSourceRecordMapper.insert(fresh);
            return IngestOutcome.INSERTED;
        }

        // Same source, same posting: this board is the authority on its own links, so
        // refreshing them here is correct and is not a cross-source overwrite.
        existing.setCatalogJobId(catalogJobId);
        existing.setSourceUrl(canonicalUrl);
        existing.setApplyUrl(applyUrl);
        existing.setPayloadHash(contentHash);
        existing.setRawPayload(job.rawPayload());
        existing.setLastSeenAt(seenAt);
        existing.setLastSuccessfulRunId(runId);
        existing.setActive(true);
        jobSourceRecordMapper.update(existing);
        return IngestOutcome.UPDATED;
    }

    /**
     * Content only. The catalog's {@code canonical_url} and {@code apply_url} are
     * owned by {@link #refreshEffectiveLinks(Long)} and are deliberately not written
     * here, so a second board merging into an existing row cannot silently replace the
     * first board's apply link.
     */
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
        entry.setPostedAt(job.postedAt());
        if (entry.getFirstSeenAt() == null) {
            entry.setFirstSeenAt(seenAt);
        }
        entry.setLastSeenAt(seenAt);
        entry.setStatus(CatalogJobStatus.ACTIVE.name());
        // A candidate signal for duplicate review, never a merge key.
        entry.setFingerprint(CatalogFingerprint.of(job.company(), job.title(), job.location()));
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
}
