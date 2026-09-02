package aijob.collector.jsonl;

import aijob.collector.IngestOutcome;
import aijob.collector.JobIngestTransactions;
import aijob.collector.NormalizedJob;
import aijob.entity.JobCatalogEntry;
import aijob.entity.JobIngestRun;
import aijob.entity.JobSource;
import aijob.entity.JobSourceRecord;
import aijob.enums.AtsProviderType;
import aijob.enums.EmploymentType;
import aijob.enums.IngestRunStatus;
import aijob.enums.RemoteType;
import aijob.mapper.JobCatalogMapper;
import aijob.mapper.JobIngestRunMapper;
import aijob.mapper.JobSourceMapper;
import aijob.mapper.JobSourceRecordMapper;
import aijob.util.CatalogFingerprint;
import aijob.util.HtmlSanitizer;
import aijob.util.HtmlTextExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Imports a JSONL batch exported by the Go collector harness into {@code job_catalog}.
 *
 * <p>This is an offline, local entry point. It opens no socket, registers no HTTP route,
 * and schedules nothing: a file on disk goes in, catalog rows come out.
 *
 * <p>It adds no ingestion logic. Identity, de-duplication, atomicity and the effective-link
 * rule all come from {@link JobIngestTransactions#upsert}, the same path the Java collector
 * uses, so an imported posting is stored exactly like a crawled one.
 *
 * <h2>A sample is not a snapshot</h2>
 * An import only ever inserts and updates. It never expires, closes or deletes a posting,
 * and it never calls {@code markSuccess} on the source — a batch of 50 postings from a
 * board with 89 says nothing about the other 39, and treating "everything I did not see is
 * gone" as the rule would close them. The run is recorded as
 * {@link IngestRunStatus#SAMPLE_IMPORT} rather than SUCCESS for the same reason.
 *
 * <h2>Nothing is written until everything validates</h2>
 * The manifest, the line count, every record's batch identity and every record's required
 * fields are checked in full before the first insert. A failed manifest, a batch/file
 * mismatch or a corrupt line is refused with the catalog untouched.
 */
@Component
public class JobExportImporter {

    private static final Logger log = LoggerFactory.getLogger(JobExportImporter.class);

    /** Bounds on an untrusted file, all deliberately far above a realistic export. */
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    static final long MAX_MANIFEST_BYTES = 1L * 1024 * 1024;
    static final int MAX_LINE_CHARS = 1024 * 1024;
    static final int MAX_RECORDS = 5000;

    /**
     * Providers whose exported timestamp really is a publication date.
     *
     * <p>Lever's adapter fills it from the posting's {@code createdAt}. Greenhouse's
     * board API publishes no such date — its adapter fills the same field from
     * {@code updated_at}, a last-modified stamp — so a Greenhouse import stores no
     * {@code posted_at} at all. Observed in the sample this was built against: 35 of 50
     * postings shared one two-minute window, which is a board-wide edit, not 35
     * publications. An unknown date stays unknown; the crawl time is never substituted,
     * because that would make every imported posting look brand new.
     */
    private static final Set<AtsProviderType> TIMESTAMP_IS_PUBLICATION_DATE =
            EnumSet.of(AtsProviderType.LEVER);

    private final JobSourceMapper jobSourceMapper;
    private final JobSourceRecordMapper jobSourceRecordMapper;
    private final JobCatalogMapper jobCatalogMapper;
    private final JobIngestRunMapper jobIngestRunMapper;
    private final JobIngestTransactions transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JobExportImporter(JobSourceMapper jobSourceMapper,
                             JobSourceRecordMapper jobSourceRecordMapper,
                             JobCatalogMapper jobCatalogMapper,
                             JobIngestRunMapper jobIngestRunMapper,
                             JobIngestTransactions transactions,
                             ObjectMapper objectMapper,
                             @Qualifier("jobSearchClock") Clock clock) {
        this.jobSourceMapper = jobSourceMapper;
        this.jobSourceRecordMapper = jobSourceRecordMapper;
        this.jobCatalogMapper = jobCatalogMapper;
        this.jobIngestRunMapper = jobIngestRunMapper;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Validates and imports one batch.
     *
     * @throws JobExportRejectedException before any write, when the batch cannot be trusted
     */
    public JobExportImportSummary importBatch(Path jsonlPath, Path manifestPath) {
        JobExportManifest manifest = readManifest(manifestPath);
        AtsProviderType provider = validateManifest(manifest, manifestPath);
        List<JobExportRecord> records = readRecords(jsonlPath, manifest);

        // The board may already have postings from an earlier import. Look the source up
        // WITHOUT creating it, so this last check still runs before anything is written:
        // a board with no source row yet has nothing to collide with.
        JobSource known = jobSourceMapper.findByProviderAndTenant(provider.name(), manifest.board());
        if (known != null) {
            rejectCrossBatchUrlCollisions(known, records);
        }

        // Everything below this line writes. Everything above it can still refuse.
        LocalDateTime seenAt = now();
        JobSource source = known != null ? known : createSource(provider, manifest);
        JobIngestRun run = startRun(source, seenAt);

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (JobExportRecord record : records) {
            try {
                IngestOutcome outcome = transactions.upsert(
                        source, toNormalizedJob(provider, record), run.getId(), seenAt);
                switch (outcome) {
                    case INSERTED -> inserted++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                }
            } catch (RuntimeException ex) {
                failed++;
                // The posting's whole write was rolled back by the transaction boundary,
                // so nothing partial is left. The payload is third-party content of
                // unknown shape and is never logged.
                log.warn("Skipped {} from batch {} ({})",
                        record.identity(), manifest.runId(), ex.getClass().getSimpleName());
            }
        }

        finishRun(run, records.size(), inserted, updated, unchanged, failed, manifest);

        // Deliberately absent: jobSourceMapper.markSuccess(...) and any expiry sweep. A
        // sample import must not advance the source's crawl state or close what it did
        // not carry.
        return new JobExportImportSummary(
                manifest.runId(), manifest.provider(), manifest.board(),
                source.getId(), run.getId(), records.size(),
                inserted, updated, unchanged, failed, manifest.isCompleteSnapshot());
    }

    // ---------------------------------------------------------------------------
    // validation
    // ---------------------------------------------------------------------------

    private JobExportManifest readManifest(Path path) {
        requireReadableFile(path, "manifest", MAX_MANIFEST_BYTES);
        try {
            String json = readUtf8(path, MAX_MANIFEST_BYTES);
            JobExportManifest manifest = objectMapper.readValue(json, JobExportManifest.class);
            if (manifest == null) {
                throw new JobExportRejectedException("Manifest " + path + " is empty");
            }
            return manifest;
        } catch (IOException ex) {
            throw new JobExportRejectedException(
                    "Manifest " + path + " is not readable JSON: " + ex.getMessage());
        }
    }

    private AtsProviderType validateManifest(JobExportManifest manifest, Path path) {
        if (!manifest.succeeded()) {
            // The exporter writes a manifest even when it failed, and writes no JSONL.
            // A stale JSONL from an earlier run can still be sitting beside it, which is
            // exactly the file this refusal keeps out of the catalog.
            throw new JobExportRejectedException(
                    "Manifest " + path + " reports status=" + manifest.status()
                            + (manifest.error() == null ? "" : " (" + manifest.error() + ")")
                            + "; refusing to import a batch its own exporter did not complete");
        }
        requireText(manifest.runId(), "manifest run_id");
        requireText(manifest.provider(), "manifest provider");
        requireText(manifest.board(), "manifest board");
        if (manifest.exportedCount() == null || manifest.exportedCount() < 0) {
            throw new JobExportRejectedException("Manifest " + path + " has no usable exported_count");
        }
        if (manifest.exportedCount() > MAX_RECORDS) {
            throw new JobExportRejectedException(
                    "Manifest declares " + manifest.exportedCount()
                            + " records, above the " + MAX_RECORDS + " limit for one batch");
        }
        AtsProviderType provider = parseProvider(manifest.provider());
        if (provider == null) {
            throw new JobExportRejectedException(
                    "Manifest provider " + manifest.provider() + " has no adapter in this deployment");
        }
        return provider;
    }

    /**
     * Reads every line and validates it against the manifest before returning. The whole
     * batch is materialised on purpose: a line-by-line import that failed halfway would
     * leave a partial batch behind, and the point of the manifest is that a batch is
     * accepted or refused as a unit.
     */
    private List<JobExportRecord> readRecords(Path path, JobExportManifest manifest) {
        requireReadableFile(path, "export", MAX_FILE_BYTES);

        List<JobExportRecord> records = new ArrayList<>();
        Set<String> seenIdentities = new HashSet<>();
        Set<String> seenIdentityUrls = new HashSet<>();
        // A strict decoder: a byte sequence that is not UTF-8 raises rather than being
        // silently replaced with U+FFFD, which would corrupt a job description quietly.
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), decoder))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (line.length() > MAX_LINE_CHARS) {
                    throw new JobExportRejectedException(
                            "Line " + lineNumber + " is " + line.length()
                                    + " characters, above the " + MAX_LINE_CHARS + " limit");
                }
                if (records.size() >= MAX_RECORDS) {
                    throw new JobExportRejectedException(
                            "Export holds more than " + MAX_RECORDS + " records");
                }
                records.add(validateRecord(parseRecord(line, lineNumber), manifest, lineNumber,
                        seenIdentities, seenIdentityUrls));
            }
        } catch (CharacterCodingException ex) {
            throw new JobExportRejectedException("Export " + path + " is not valid UTF-8");
        } catch (IOException ex) {
            throw new JobExportRejectedException("Export " + path + " is not readable: " + ex.getMessage());
        }

        if (records.size() != manifest.exportedCount()) {
            // The manifest and the file disagree, so at least one of them is stale or
            // truncated and there is no way to tell which. Refuse both.
            throw new JobExportRejectedException(
                    "Export holds " + records.size() + " records but the manifest declares "
                            + manifest.exportedCount() + "; refusing a batch whose file and"
                            + " manifest disagree");
        }
        return records;
    }

    private JobExportRecord parseRecord(String line, int lineNumber) {
        try {
            JobExportRecord record = objectMapper.readValue(line, JobExportRecord.class);
            if (record == null) {
                throw new JobExportRejectedException("Line " + lineNumber + " is empty JSON");
            }
            return record;
        } catch (IOException ex) {
            throw new JobExportRejectedException(
                    "Line " + lineNumber + " is not a valid JSON record: " + ex.getMessage());
        }
    }

    private JobExportRecord validateRecord(JobExportRecord record,
                                           JobExportManifest manifest,
                                           int lineNumber,
                                           Set<String> seenIdentities,
                                           Set<String> seenIdentityUrls) {
        if (!manifest.runId().equals(record.runId())) {
            throw new JobExportRejectedException(
                    "Line " + lineNumber + " carries run_id " + record.runId()
                            + " but the manifest is " + manifest.runId()
                            + "; refusing a file mixed from more than one batch");
        }
        if (!manifest.provider().equals(record.provider()) || !manifest.board().equals(record.board())) {
            throw new JobExportRejectedException(
                    "Line " + lineNumber + " is " + record.provider() + "/" + record.board()
                            + " but the manifest is " + manifest.provider() + "/" + manifest.board());
        }
        requireText(record.externalId(), "line " + lineNumber + " external_id");
        requireNoSurroundingWhitespace(record.externalId(), "line " + lineNumber + " external_id");
        requireText(record.title(), "line " + lineNumber + " title");
        requireText(record.company(), "line " + lineNumber + " company");
        requireText(record.applyUrl(), "line " + lineNumber + " apply_url");
        if (!seenIdentities.add(record.identity())) {
            throw new JobExportRejectedException(
                    "Line " + lineNumber + " repeats identity " + record.identity()
                            + " within one batch");
        }
        // Cross-source de-duplication merges on an identity URL, and that check is a
        // heuristic: it excludes careers landing pages, but it cannot prove that a board
        // gives every posting its own address. Two postings in ONE batch sharing one
        // identity URL is direct evidence that this board does not — and importing them
        // would collapse them into a single catalog row.
        //
        // Refusing is the honest response: the batch is intact, the board's URL scheme is
        // not what de-duplication assumes, and that is a decision for an operator rather
        // than something to silently absorb. Distinct postings that legitimately share a
        // page would need a per-provider rule, which is out of scope here.
        String identityUrl = CatalogFingerprint.identityUrl(record.applyUrl());
        if (identityUrl != null && !seenIdentityUrls.add(identityUrl)) {
            throw new JobExportRejectedException(
                    "Line " + lineNumber + " (" + record.identity() + ") reuses the apply URL "
                            + record.applyUrl() + " already claimed by another posting in this"
                            + " batch; importing both would merge two distinct requisitions"
                            + " into one catalog row");
        }
        return record;
    }

    private void requireReadableFile(Path path, String what, long maxBytes) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new JobExportRejectedException("No " + what + " file at " + path);
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException ex) {
            throw new JobExportRejectedException("Cannot size " + what + " " + path);
        }
        if (size == 0) {
            throw new JobExportRejectedException(what + " " + path + " is empty");
        }
        if (size > maxBytes) {
            throw new JobExportRejectedException(
                    what + " " + path + " is " + size + " bytes, above the " + maxBytes + " limit");
        }
    }

    private static String readUtf8(Path path, long maxBytes) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > maxBytes) {
            throw new JobExportRejectedException(path + " is larger than " + maxBytes + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new JobExportRejectedException("Missing " + what);
        }
    }

    /**
     * Refuses an identifier padded with whitespace, rather than trimming it.
     *
     * <p>Trimming would be the quiet failure: {@code "REQ-A "} would be stored as
     * {@code "REQ-A"} and from then on be a DIFFERENT posting's identity, so two
     * requisitions could merge because this code rewrote one of their ids. The column's
     * collation is NO PAD precisely so the database stops treating them as one, and it
     * would be undone by a strip() here.
     *
     * <p>Keeping the padded value verbatim is the other consistent option, but it makes
     * an invisible character part of an identity operators have to match by eye. A
     * padded id is far more likely an export bug than something the board published, so
     * it is refused loudly and the batch is fixed at the source.
     */
    private static void requireNoSurroundingWhitespace(String value, String what) {
        if (value != null && !value.equals(value.strip())) {
            throw new JobExportRejectedException(
                    "Rejected " + what + ": it is padded with whitespace. The value is stored"
                            + " exactly as given and is never trimmed, because trimming would"
                            + " silently turn it into a different posting's id");
        }
    }

    // ---------------------------------------------------------------------------
    // mapping
    // ---------------------------------------------------------------------------

    /**
     * Maps an exported record onto the shape ingestion already understands.
     *
     * <p>Absent signals stay absent. Greenhouse publishes no employment type, workplace
     * type or country for most postings, and inventing ONSITE or a country code from a
     * location string would put a guess where the schema promises a platform-stated fact.
     */
    NormalizedJob toNormalizedJob(AtsProviderType provider, JobExportRecord record) {
        String descriptionHtml = HtmlSanitizer.sanitize(record.description());
        String descriptionText = HtmlTextExtractor.toPlainText(record.description());

        return new NormalizedJob(
                provider.name(),
                record.board(),
                record.externalId(),
                record.title(),
                record.company(),
                descriptionText,
                descriptionHtml,
                // The board's own location string, kept whole. A multi-city value such as
                // "Hybrid - San Francisco, New York City, Austin" is stored verbatim;
                // splitting it is a separate change, and truncating it here would lose
                // what the employer actually published.
                record.location(),
                firstCountry(record),
                remoteTypeOf(record),
                EmploymentType.fromNullable(record.employmentType()),
                // No salary in the export: the adapters that publish one set it
                // structurally, and none of them did here.
                null,
                record.applyUrl(),
                record.applyUrl(),
                publicationDate(provider, record),
                rawPayload(record));
    }

    /**
     * The publication date, or null when the provider does not publish one.
     *
     * <p>See {@link #TIMESTAMP_IS_PUBLICATION_DATE}. The alternative — storing a
     * last-modified stamp as {@code posted_at} — would make "posted within 7 days" a
     * filter on when the employer last edited the page.
     */
    private LocalDateTime publicationDate(AtsProviderType provider, JobExportRecord record) {
        if (!TIMESTAMP_IS_PUBLICATION_DATE.contains(provider)) {
            return null;
        }
        return parseIsoInstant(record.postedAt());
    }

    private static LocalDateTime parseIsoInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(java.time.Instant.parse(value.strip()), ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * REMOTE only on evidence. {@code remote=false} means the location text did not say
     * "remote", which is not the same as the employer stating the role is on-site.
     */
    private static RemoteType remoteTypeOf(JobExportRecord record) {
        RemoteType stated = RemoteType.fromNullable(record.workMode());
        if (stated != RemoteType.UNSPECIFIED) {
            return stated;
        }
        return Boolean.TRUE.equals(record.remote()) ? RemoteType.REMOTE : RemoteType.UNSPECIFIED;
    }

    private static String firstCountry(JobExportRecord record) {
        if (record.countries() == null || record.countries().isEmpty()) {
            return null;
        }
        String first = record.countries().get(0);
        if (first == null || first.isBlank()) {
            return null;
        }
        return first.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * The record as the exporter wrote it, kept on the source record. This is where the
     * fields the catalog columns cannot express survive — including the provider
     * timestamp that {@link #publicationDate} declined to treat as a publication date.
     */
    private String rawPayload(JobExportRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (IOException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------
    // source and run bookkeeping
    // ---------------------------------------------------------------------------

    /**
     * Finds, or creates, the {@code job_sources} row for this provider+board pair.
     *
     * <p>A row created here starts with both opt-in flags OFF. The Java collector must
     * not begin crawling a board just because someone imported a file exported from it;
     * enabling a board stays a separate, deliberate act.
     */
    /**
     * Refuses a batch whose postings would land on a catalog row this source already owns
     * under a different requisition.
     *
     * <p>This is the cross-batch half of the in-batch check in {@code validateRecord}. A
     * posting that is new to this source is resolved by identity URL, so if an earlier
     * import already put that URL on a catalog row under external id A, arriving with the
     * same URL under external id B merges the two — and, because the merge rewrites the
     * row's content, the earlier requisition's title and description are simply replaced.
     * Measured before this check existed: two requisitions, one catalog row, the first
     * one's title gone.
     *
     * <p>Refusing rather than de-duplicating is deliberate. The evidence says this board
     * does not give each posting its own address, which is a property of the board — not
     * something an importer should paper over by inventing a second merge key.
     */
    private void rejectCrossBatchUrlCollisions(JobSource source, List<JobExportRecord> records) {
        for (JobExportRecord record : records) {
            if (jobSourceRecordMapper.findBySourceAndExternalId(source.getId(), record.externalId()) != null) {
                continue; // known posting: identity is exact, nothing to resolve by URL
            }
            String identityUrl = CatalogFingerprint.identityUrl(record.applyUrl());
            if (identityUrl == null) {
                continue; // not a merge key at all (a careers landing page, say)
            }
            JobCatalogEntry match = jobCatalogMapper.findByAnyUrlNorm(identityUrl);
            if (match == null) {
                continue;
            }
            JobSourceRecord owner = jobSourceRecordMapper.findBySourceAndCatalogJob(
                    source.getId(), match.getId());
            if (owner != null && !owner.getExternalJobId().equals(record.externalId())) {
                throw new JobExportRejectedException(
                        record.identity() + " would land on the posting this board already"
                                + " imported as " + owner.getExternalJobId() + ": both carry the"
                                + " apply URL " + record.applyUrl() + ". Importing it would merge"
                                + " two distinct requisitions into one catalog row and overwrite"
                                + " the first one's content.");
            }
        }
    }

    private JobSource createSource(AtsProviderType provider, JobExportManifest manifest) {
        JobSource source = new JobSource();
        source.setProviderType(provider.name());
        source.setTenantKey(manifest.board());
        source.setDisplayName(manifest.company() == null || manifest.company().isBlank()
                ? manifest.board()
                : manifest.company());
        source.setEnabled(false);
        source.setCrawlAllowed(false);
        source.setCollectionIntervalMinutes(120);
        jobSourceMapper.insert(source);
        log.info("Created job source {} for {}/{}, disabled", source.getId(), provider, manifest.board());
        return source;
    }

    private JobIngestRun startRun(JobSource source, LocalDateTime startedAt) {
        JobIngestRun run = new JobIngestRun();
        run.setSourceId(source.getId());
        run.setStartedAt(startedAt);
        run.setStatus(IngestRunStatus.RUNNING.name());
        jobIngestRunMapper.insert(run);
        return run;
    }

    private void finishRun(JobIngestRun run, int fetched, int inserted, int updated,
                           int unchanged, int failed, JobExportManifest manifest) {
        run.setFinishedAt(now());
        run.setStatus(IngestRunStatus.SAMPLE_IMPORT.name());
        run.setFetchedCount(fetched);
        run.setInsertedCount(inserted);
        run.setUpdatedCount(updated);
        run.setUnchangedCount(unchanged);
        // Nothing expired: an import has no expiry path at all.
        run.setExpiredCount(0);
        run.setFailedCount(failed);
        // Short, classified provenance only — never a payload, a key or personal data.
        run.setErrorSummary("IMPORT run=" + manifest.runId()
                + " complete_snapshot=" + manifest.isCompleteSnapshot());
        jobIngestRunMapper.finish(run);
    }

    private static AtsProviderType parseProvider(String value) {
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
