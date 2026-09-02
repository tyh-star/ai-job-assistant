package aijob.collector;

import aijob.entity.JobCatalogEntry;
import aijob.entity.JobIngestRun;
import aijob.entity.JobSource;
import aijob.entity.JobSourceRecord;
import aijob.enums.AtsProviderType;
import aijob.enums.CatalogJobStatus;
import aijob.enums.EmploymentType;
import aijob.enums.IngestRunStatus;
import aijob.enums.RemoteType;
import aijob.AiJobApplication;
import aijob.mapper.JobCatalogMapper;
import aijob.mapper.JobIngestRunMapper;
import aijob.mapper.JobSourceMapper;
import aijob.mapper.JobSourceRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ingestion against a real database: upsert, de-duplication and expiry all depend on
 * unique keys and index lookups, so they are exercised through the actual SQL rather
 * than mocked mappers. The board adapter is a stub, so no network is touched.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jobingest;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
        }
)
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class JobIngestH2IntegrationTest {

    private static final LocalDateTime DAY_1 = LocalDateTime.of(2026, 8, 1, 9, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobCatalogMapper jobCatalogMapper;

    @Autowired
    private JobSourceMapper jobSourceMapper;

    @Autowired
    private JobSourceRecordMapper jobSourceRecordMapper;

    @Autowired
    private JobIngestRunMapper jobIngestRunMapper;

    /**
     * Injected rather than constructed by hand on purpose: the transactional boundaries
     * live in this bean's proxy, and a hand-built instance would silently have none.
     */
    @Autowired
    private JobIngestTransactions transactions;

    private JobCollectorProperties properties;
    private StubAdapter greenhouse;
    private MutableClock clock;
    private JobIngestServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new JobCollectorProperties();
        properties.setExpiryGraceHours(72);
        greenhouse = new StubAdapter(AtsProviderType.GREENHOUSE);
        clock = new MutableClock(DAY_1);
        service = newService("collector-a");
    }

    private JobIngestServiceImpl newService(String owner) {
        return new JobIngestServiceImpl(
                List.of(greenhouse),
                jobSourceMapper,
                jobSourceRecordMapper,
                jobIngestRunMapper,
                transactions,
                properties,
                clock,
                owner);
    }

    @Test
    void firstRunInsertsAndSecondIdenticalRunReportsUnchanged() {
        JobSource source = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));

        JobIngestRun first = service.collectSource(source);
        assertEquals(IngestRunStatus.SUCCESS.name(), first.getStatus());
        assertEquals(1, first.getInsertedCount());
        assertEquals(1, countCatalog());

        clock.advanceHours(1);
        JobIngestRun second = service.collectSource(jobSourceMapper.findById(source.getId()));
        assertEquals(1, second.getUnchangedCount());
        assertEquals(0, second.getInsertedCount());
        assertEquals(1, countCatalog());
    }

    @Test
    void changedPayloadUpdatesTheCatalogEntryInPlace() {
        JobSource source = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(source);
        Long catalogId = onlyCatalogId();

        clock.advanceHours(1);
        greenhouse.jobs = List.of(job("gh-1", "Senior Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        JobIngestRun run = service.collectSource(jobSourceMapper.findById(source.getId()));

        assertEquals(1, run.getUpdatedCount());
        assertEquals(1, countCatalog());
        assertEquals(catalogId, onlyCatalogId());
        assertEquals("Senior Java Developer", jobCatalogMapper.findById(catalogId).getTitle());
    }

    @Test
    void twoSourcesPublishingTheSameApplyUrlShareOneCatalogEntry() {
        JobSource acme = source("GREENHOUSE", "acme");
        JobSource acmeMirror = source("GREENHOUSE", "acme-eu");

        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(acme);

        // Same posting, different external id, and a URL that differs only in
        // tracking parameters and a trailing slash.
        greenhouse.jobs = List.of(job("eu-9", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1/?utm_source=eu"));
        service.collectSource(acmeMirror);

        assertEquals(1, countCatalog());
        assertEquals(2, countSourceRecords());
    }

    @Test
    void identicalPostingsWithoutAUrlMatchAreKeptApartRatherThanMergedOnFingerprint() {
        JobSource acme = source("GREENHOUSE", "acme");
        JobSource mirror = source("GREENHOUSE", "acme-mirror");

        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(acme);

        greenhouse.jobs = List.of(job("mr-1", "java developer", "ACME", "toronto, on",
                "https://boards.greenhouse.io/acme-mirror/jobs/77"));
        service.collectSource(mirror);

        // Company, title and location agree, and nothing else does. That is not proof
        // of one opening, so both survive; a human decides via the duplicate report.
        assertEquals(2, countCatalog());
    }

    @Test
    void oneCompanyCanRunTwoOpeningsWithTheSameTitleInTheSameCity() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(
                job("REQ-4001", "Java Developer", "Acme", "Toronto, ON",
                        "https://boards.greenhouse.io/acme/jobs/4001"),
                job("REQ-4002", "Java Developer", "Acme", "Toronto, ON",
                        "https://boards.greenhouse.io/acme/jobs/4002"));

        service.collectSource(acme);

        // Two requisition numbers are two jobs, whatever the title says.
        assertEquals(2, countCatalog());
        assertEquals(2, countSourceRecords());
        List<String> applyUrls = jdbcTemplate.queryForList(
                "SELECT apply_url FROM job_catalog ORDER BY id", String.class);
        assertEquals(List.of("https://boards.greenhouse.io/acme/jobs/4001",
                "https://boards.greenhouse.io/acme/jobs/4002"), applyUrls);
    }

    @Test
    void postingsThatDifferOnlyInTheirJobIdParameterAreNotMerged() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(
                job("REQ-1", "Java Developer", "Acme", "Toronto, ON",
                        "https://acme.example.com/careers?gh_jid=1001"),
                job("REQ-2", "Java Developer", "Acme", "Toronto, ON",
                        "https://acme.example.com/careers?gh_jid=1002"));

        service.collectSource(acme);

        // The identifier lives in the query string here. Stripping the query would
        // reduce this whole board to one row.
        assertEquals(2, countCatalog());
    }

    @Test
    void aSharedCareersPageIsNeverUsedAsAMergeKey() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(
                job("REQ-1", "Java Developer", "Acme", "Toronto, ON",
                        "https://acme.example.com/careers"),
                job("REQ-2", "Data Engineer", "Acme", "Vancouver, BC",
                        "https://acme.example.com/careers"),
                job("REQ-3", "Product Manager", "Acme", "Montreal, QC",
                        "https://acme.example.com/careers"));

        service.collectSource(acme);

        // Same generic landing page for every posting; merging on it would collapse
        // the board into a single row.
        assertEquals(3, countCatalog());
        List<String> norms = jdbcTemplate.queryForList(
                "SELECT apply_url_norm FROM job_catalog", String.class);
        assertTrue(norms.stream().allMatch(Objects::isNull),
                "a careers landing page must not be stored as a de-duplication key");
    }

    @Test
    void trackingParametersStillDoNotSplitOnePosting() {
        JobSource acme = source("GREENHOUSE", "acme");
        JobSource campaign = source("GREENHOUSE", "acme-campaign");

        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1?gh_jid=1&utm_source=newsletter"));
        service.collectSource(acme);

        greenhouse.jobs = List.of(job("cm-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1?utm_campaign=spring&gh_jid=1"));
        service.collectSource(campaign);

        // Campaign parameters are dropped, gh_jid is kept, so this really is one job.
        assertEquals(1, countCatalog());
        assertEquals(2, countSourceRecords());
    }

    @Test
    void theSameTitleAtDifferentPlacesIsNotMerged() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(
                job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                        "https://boards.greenhouse.io/acme/jobs/1"),
                job("gh-2", "Java Developer", "Globex", "Toronto, ON",
                        "https://boards.greenhouse.io/acme/jobs/2"),
                job("gh-3", "Java Developer", "Acme", "Vancouver, BC",
                        "https://boards.greenhouse.io/acme/jobs/3"));

        service.collectSource(acme);

        assertEquals(3, countCatalog());
    }

    @Test
    void postingsWithoutALocationAreNeverMergedOnTitleAlone() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(
                job("gh-1", "Java Developer", "Acme", null,
                        "https://boards.greenhouse.io/acme/jobs/1"),
                job("gh-2", "Java Developer", "Acme", null,
                        "https://boards.greenhouse.io/acme/jobs/2"));

        service.collectSource(acme);

        assertEquals(2, countCatalog());
    }

    @Test
    void aFailedRunExpiresNothing() {
        JobSource source = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(source);

        clock.advance(DAY_1.plusDays(30));
        greenhouse.failure = new JobCollectionException("SOURCE_UNAVAILABLE_503");
        JobIngestRun run = service.collectSource(jobSourceMapper.findById(source.getId()));

        assertEquals(IngestRunStatus.FAILED.name(), run.getStatus());
        assertEquals(0, run.getExpiredCount());
        assertEquals(CatalogJobStatus.ACTIVE.name(),
                jobCatalogMapper.findById(onlyCatalogId()).getStatus());
        assertEquals(1, jobSourceMapper.findById(source.getId()).getConsecutiveFailures());
    }

    @Test
    void aMissingPostingSurvivesTheGracePeriodAndOnlyThenExpires() {
        JobSource source = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(source);
        Long catalogId = onlyCatalogId();

        // Inside the 72h grace period: the board simply stopped listing it this tick.
        clock.advance(DAY_1.plusHours(24));
        greenhouse.jobs = List.of();
        JobIngestRun early = service.collectSource(jobSourceMapper.findById(source.getId()));
        assertEquals(0, early.getExpiredCount());
        assertEquals(CatalogJobStatus.ACTIVE.name(), jobCatalogMapper.findById(catalogId).getStatus());

        clock.advance(DAY_1.plusHours(96));
        JobIngestRun late = service.collectSource(jobSourceMapper.findById(source.getId()));
        assertEquals(1, late.getExpiredCount());
        assertEquals(CatalogJobStatus.EXPIRED.name(), jobCatalogMapper.findById(catalogId).getStatus());
    }

    @Test
    void anExpiredPostingIsRestoredWhenTheBoardListsItAgain() {
        JobSource source = source("GREENHOUSE", "acme");
        NormalizedJob posting = job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1");
        greenhouse.jobs = List.of(posting);
        service.collectSource(source);
        Long catalogId = onlyCatalogId();

        clock.advance(DAY_1.plusHours(96));
        greenhouse.jobs = List.of();
        service.collectSource(jobSourceMapper.findById(source.getId()));
        assertEquals(CatalogJobStatus.EXPIRED.name(), jobCatalogMapper.findById(catalogId).getStatus());

        clock.advance(DAY_1.plusHours(120));
        greenhouse.jobs = List.of(posting);
        service.collectSource(jobSourceMapper.findById(source.getId()));

        assertEquals(CatalogJobStatus.ACTIVE.name(), jobCatalogMapper.findById(catalogId).getStatus());
        assertEquals(1, countCatalog());
    }

    @Test
    void aPostingStillPublishedByAnotherSourceIsNotExpired() {
        JobSource acme = source("GREENHOUSE", "acme");
        JobSource mirror = source("GREENHOUSE", "acme-mirror");
        NormalizedJob posting = job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1");

        greenhouse.jobs = List.of(posting);
        service.collectSource(acme);
        greenhouse.jobs = List.of(job("mr-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(mirror);
        Long catalogId = onlyCatalogId();

        clock.advance(DAY_1.plusHours(96));
        greenhouse.jobs = List.of();
        service.collectSource(jobSourceMapper.findById(acme.getId()));

        assertEquals(CatalogJobStatus.ACTIVE.name(), jobCatalogMapper.findById(catalogId).getStatus());
    }

    @Test
    void aSourceThatIsNotOptedInIsNeverCollected() {
        JobSource disabled = source("GREENHOUSE", "not-approved", false, true);
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));

        JobIngestRun run = service.collectSource(disabled);

        assertEquals(IngestRunStatus.FAILED.name(), run.getStatus());
        assertEquals("SOURCE_NOT_COLLECTABLE", run.getErrorSummary());
        assertEquals(0, greenhouse.fetchCount);
        assertEquals(0, countCatalog());
    }

    @Test
    void findDueSkipsSourcesTheOperatorHasNotApproved() {
        source("GREENHOUSE", "approved", true, true);
        source("GREENHOUSE", "disabled", false, true);
        source("GREENHOUSE", "crawl-not-allowed", true, false);

        List<JobSource> due = jobSourceMapper.findDue(DAY_1, 100);

        assertEquals(1, due.size());
        assertEquals("approved", due.get(0).getTenantKey());
    }

    @Test
    void runRowsRecordCountsAndKeepErrorSummariesFreeOfPayload() {
        JobSource source = source("GREENHOUSE", "acme");
        greenhouse.failure = new JobCollectionException("SOURCE_REJECTED_403");

        JobIngestRun run = service.collectSource(source);

        JobIngestRun stored = jobIngestRunMapper.findById(run.getId());
        assertNotNull(stored);
        assertEquals(IngestRunStatus.FAILED.name(), stored.getStatus());
        assertEquals("SOURCE_REJECTED_403", stored.getErrorSummary());
        assertTrue(stored.getErrorSummary().length() <= 500);
    }

    @Test
    void housekeepingDropsStoredPayloadsForLongGonePostings() {
        properties.setRawPayloadRetentionDays(1);
        JobSource source = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(source);

        clock.advance(DAY_1.plusHours(96));
        greenhouse.jobs = List.of();
        service.collectSource(jobSourceMapper.findById(source.getId()));

        clock.advance(DAY_1.plusDays(30));
        service.housekeeping();

        JobSourceRecord record =
                jobSourceRecordMapper.findBySourceAndExternalId(source.getId(), "gh-1");
        assertNull(record.getRawPayload());
        // Attribution survives: the source link and URLs are still there.
        assertNotNull(record.getSourceUrl());
        assertNotNull(record.getFirstSeenAt());
    }

    @Test
    void aStoredCatalogEntryKeepsItsFirstSeenTimestampAcrossUpdates() {
        JobSource source = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(source);
        JobCatalogEntry initial = jobCatalogMapper.findById(onlyCatalogId());

        clock.advanceHours(48);
        greenhouse.jobs = List.of(job("gh-1", "Staff Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(jobSourceMapper.findById(source.getId()));

        JobCatalogEntry updated = jobCatalogMapper.findById(onlyCatalogId());
        assertEquals(initial.getFirstSeenAt(), updated.getFirstSeenAt());
        assertNotEquals(initial.getLastSeenAt(), updated.getLastSeenAt());
    }

    // ---------------------------------------------------------------------------
    // Atomicity
    // ---------------------------------------------------------------------------

    @Test
    void aFailedSourceRecordWriteRollsBackTheCatalogRowItWouldHavePointedAt() {
        JobSource acme = source("GREENHOUSE", "acme");
        // job_source_records.external_job_id is VARCHAR(190); this one is not storable,
        // so the record insert fails AFTER the catalog row has already been written.
        String overlongExternalId = "R".repeat(300);
        greenhouse.jobs = List.of(
                job(overlongExternalId, "Java Developer", "Acme", "Toronto, ON",
                        "https://boards.greenhouse.io/acme/jobs/1"));

        JobIngestRun run = service.collectSource(acme);

        assertEquals(1, run.getFailedCount());
        assertEquals(0, run.getInsertedCount());
        // The whole unit of work was undone: no orphan catalog row, no half-written
        // record. Before the transactional boundary was real, this left a catalog row
        // that nothing referenced and that the next run would duplicate.
        assertEquals(0, countCatalog(), "a rolled-back posting must leave no catalog row");
        assertEquals(0, countSourceRecords());
    }

    @Test
    void oneUnusablePostingDoesNotRollBackTheGoodOnesAroundIt() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(
                job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                        "https://boards.greenhouse.io/acme/jobs/1"),
                job("R".repeat(300), "Broken Posting", "Acme", "Toronto, ON",
                        "https://boards.greenhouse.io/acme/jobs/2"),
                job("gh-3", "Data Engineer", "Acme", "Toronto, ON",
                        "https://boards.greenhouse.io/acme/jobs/3"));

        JobIngestRun run = service.collectSource(acme);

        assertEquals(2, run.getInsertedCount());
        assertEquals(1, run.getFailedCount());
        assertEquals(2, countCatalog());
    }

    @Test
    void aRunWithAnUnusablePostingExpiresNothing() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(acme);
        Long catalogId = onlyCatalogId();

        clock.advance(DAY_1.plusHours(96));
        greenhouse.jobs = List.of(
                job("R".repeat(300), "Broken Posting", "Acme", "Toronto, ON",
                        "https://boards.greenhouse.io/acme/jobs/2"));
        JobIngestRun run = service.collectSource(jobSourceMapper.findById(acme.getId()));

        // gh-1 is missing from this response, but the response was not trustworthy.
        assertEquals(1, run.getFailedCount());
        assertEquals(0, run.getExpiredCount());
        assertEquals(CatalogJobStatus.ACTIVE.name(), jobCatalogMapper.findById(catalogId).getStatus());
    }

    @Test
    void theBoardIsReadOutsideAnyDatabaseTransaction() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));

        service.collectSource(acme);

        // A board that hangs must not be holding a transaction and its row locks open.
        assertEquals(Boolean.FALSE, greenhouse.transactionActiveDuringFetch,
                "network I/O ran inside a database transaction");
    }

    // ---------------------------------------------------------------------------
    // Which source owns the catalog's links
    // ---------------------------------------------------------------------------

    @Test
    void aSecondBoardMergingIntoAPostingDoesNotOverwriteTheFirstBoardsLinks() {
        JobSource acme = source("GREENHOUSE", "acme");
        JobSource mirror = source("GREENHOUSE", "acme-mirror");

        greenhouse.jobs = List.of(jobWithLinks("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(acme);

        // Same opening, reached through a mirror that also links the canonical page.
        greenhouse.jobs = List.of(jobWithLinks("mr-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1",
                "https://mirror.example.com/apply/9"));
        service.collectSource(mirror);

        assertEquals(1, countCatalog());
        JobCatalogEntry entry = jobCatalogMapper.findById(onlyCatalogId());
        // Last writer does not win: the earliest active record still supplies the link.
        assertEquals("https://boards.greenhouse.io/acme/jobs/1", entry.getApplyUrl());
        // Nothing is lost -- the mirror's own link is on its source record.
        assertEquals("https://mirror.example.com/apply/9",
                jobSourceRecordMapper.findBySourceAndExternalId(mirror.getId(), "mr-1").getApplyUrl());
    }

    @Test
    void theSameSourceMayRefreshItsOwnApplyLink() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(jobWithLinks("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(acme);

        clock.advanceHours(4);
        greenhouse.jobs = List.of(jobWithLinks("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1",
                "https://boards.greenhouse.io/acme/jobs/1/apply"));
        service.collectSource(jobSourceMapper.findById(acme.getId()));

        // A board is the authority on its own posting's links.
        assertEquals("https://boards.greenhouse.io/acme/jobs/1/apply",
                jobCatalogMapper.findById(onlyCatalogId()).getApplyUrl());
    }

    @Test
    void aRetiredSourceStopsSupplyingTheApplyLinkWhileAnotherBoardStillHasThePosting() {
        JobSource acme = source("GREENHOUSE", "acme");
        JobSource mirror = source("GREENHOUSE", "acme-mirror");

        greenhouse.jobs = List.of(jobWithLinks("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(acme);
        greenhouse.jobs = List.of(jobWithLinks("mr-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1",
                "https://mirror.example.com/apply/9"));
        service.collectSource(mirror);
        Long catalogId = onlyCatalogId();

        // The first board drops the posting; the mirror keeps publishing it.
        clock.advance(DAY_1.plusHours(96));
        greenhouse.jobs = List.of();
        service.collectSource(jobSourceMapper.findById(acme.getId()));

        JobCatalogEntry entry = jobCatalogMapper.findById(catalogId);
        assertEquals(CatalogJobStatus.ACTIVE.name(), entry.getStatus());
        // The dead link is gone; the surviving board's link took over in the same
        // transaction that retired the old record.
        assertEquals("https://mirror.example.com/apply/9", entry.getApplyUrl());
    }

    @Test
    void anExpiredPostingKeepsItsLastKnownLink() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(acme);
        Long catalogId = onlyCatalogId();

        clock.advance(DAY_1.plusHours(96));
        greenhouse.jobs = List.of();
        service.collectSource(jobSourceMapper.findById(acme.getId()));

        JobCatalogEntry entry = jobCatalogMapper.findById(catalogId);
        assertEquals(CatalogJobStatus.EXPIRED.name(), entry.getStatus());
        assertEquals("https://boards.greenhouse.io/acme/jobs/1", entry.getApplyUrl());
    }

    @Test
    void aLinkThatCouldExecuteIsNotStored() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(jobWithLinks("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1",
                "javascript:alert(document.cookie)"));

        service.collectSource(acme);

        // The posting is still catalogued; only the unusable link is dropped.
        assertEquals(1, countCatalog());
        assertNull(jobSourceRecordMapper.findBySourceAndExternalId(acme.getId(), "gh-1").getApplyUrl());
        assertNull(jobCatalogMapper.findById(onlyCatalogId()).getApplyUrl());
    }

    // ---------------------------------------------------------------------------
    // Cross-process lease
    // ---------------------------------------------------------------------------

    @Test
    void aSecondCollectorProcessDoesNotCollectASourceTheFirstOneHolds() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));

        // Someone else -- a manual `run --rm collector` -- claimed it first.
        assertEquals(1, jobSourceMapper.tryLock(
                acme.getId(), "collector-b", DAY_1, DAY_1.minusMinutes(60)));

        JobIngestRun run = service.collectSource(acme);

        assertEquals(IngestRunStatus.SKIPPED.name(), run.getStatus());
        assertEquals("SOURCE_LOCKED", run.getErrorSummary());
        assertEquals(0, greenhouse.fetchCount, "the board must not be read twice at once");
        assertEquals(0, countCatalog());
        // A contended source is not a failing source, so it must not back off.
        assertEquals(0, jobSourceMapper.findById(acme.getId()).getConsecutiveFailures());
    }

    @Test
    void theLeaseIsReleasedAfterASuccessfulRun() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));

        service.collectSource(acme);

        assertNull(lockedBy(acme.getId()));
        // And another process can now take it.
        assertEquals(1, jobSourceMapper.tryLock(
                acme.getId(), "collector-b", DAY_1, DAY_1.minusMinutes(60)));
    }

    @Test
    void theLeaseIsReleasedAfterAFailedRun() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.failure = new JobCollectionException("SOURCE_UNAVAILABLE_503");

        JobIngestRun run = service.collectSource(acme);

        assertEquals(IngestRunStatus.FAILED.name(), run.getStatus());
        assertNull(lockedBy(acme.getId()), "a failed run must not keep a board claimed");
    }

    @Test
    void anAbandonedLeaseIsTakenOverAfterTheTimeout() {
        JobSource acme = source("GREENHOUSE", "acme");
        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        properties.setLockTimeoutMinutes(60);

        // A container that was killed mid-run left this behind.
        jobSourceMapper.tryLock(acme.getId(), "collector-gone", DAY_1.minusHours(5),
                DAY_1.minusHours(6));

        clock.advance(DAY_1);
        JobIngestRun run = service.collectSource(acme);

        assertEquals(IngestRunStatus.SUCCESS.name(), run.getStatus());
        assertEquals(1, countCatalog());
        assertNull(lockedBy(acme.getId()));
    }

    @Test
    void releasingALeaseSomeoneElseNowHoldsChangesNothing() {
        JobSource acme = source("GREENHOUSE", "acme");
        jobSourceMapper.tryLock(acme.getId(), "collector-b", DAY_1, DAY_1.minusMinutes(60));

        assertEquals(0, jobSourceMapper.releaseLock(acme.getId(), "collector-a"));

        assertEquals("collector-b", lockedBy(acme.getId()));
    }

    private String lockedBy(Long sourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT locked_by FROM job_sources WHERE id = ?", String.class, sourceId);
    }

    private JobSource source(String providerType, String tenantKey) {
        return source(providerType, tenantKey, true, true);
    }

    private JobSource source(String providerType, String tenantKey, boolean enabled, boolean crawlAllowed) {
        JobSource source = new JobSource();
        source.setProviderType(providerType);
        source.setDisplayName(tenantKey);
        source.setTenantKey(tenantKey);
        source.setEnabled(enabled);
        source.setCrawlAllowed(crawlAllowed);
        source.setCollectionIntervalMinutes(120);
        jobSourceMapper.insert(source);
        return source;
    }

    private static NormalizedJob jobWithLinks(String externalId, String title, String company,
                                              String location, String canonicalUrl, String applyUrl) {
        NormalizedJob base = job(externalId, title, company, location, canonicalUrl);
        return new NormalizedJob(
                base.provider(), base.tenant(), base.externalJobId(), base.title(), base.company(),
                base.descriptionText(), base.descriptionHtml(), base.location(), base.country(),
                base.remoteType(), base.employmentType(), base.salary(),
                canonicalUrl, applyUrl, base.postedAt(), base.rawPayload());
    }

    private static NormalizedJob job(String externalId, String title, String company,
                                     String location, String url) {
        return new NormalizedJob(
                AtsProviderType.GREENHOUSE.name(),
                "acme",
                externalId,
                title,
                company,
                "Description for " + title + " at " + company,
                "<p>Description for " + title + "</p>",
                location,
                location == null ? null : "CA",
                RemoteType.ONSITE,
                EmploymentType.FULL_TIME,
                null,
                url,
                url,
                DAY_1.minusDays(2),
                "{\"id\":\"" + externalId + "\"}");
    }

    private int countCatalog() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_catalog", Integer.class);
    }

    private int countSourceRecords() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_source_records", Integer.class);
    }

    private Long onlyCatalogId() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM job_catalog ORDER BY id", Long.class);
        return ids.get(0);
    }

    /** Returns a fixed set of postings, or fails, without any network access. */
    private static final class StubAdapter implements JobBoardAdapter {

        private final AtsProviderType providerType;
        private List<NormalizedJob> jobs = new ArrayList<>();
        private RuntimeException failure;
        private int fetchCount;
        /** Stands in for "was the network call holding a transaction open?". */
        private Boolean transactionActiveDuringFetch;

        private StubAdapter(AtsProviderType providerType) {
            this.providerType = providerType;
        }

        @Override
        public AtsProviderType providerType() {
            return providerType;
        }

        @Override
        public List<NormalizedJob> fetch(JobSource source) {
            fetchCount++;
            transactionActiveDuringFetch =
                    TransactionSynchronizationManager.isActualTransactionActive();
            if (failure != null) {
                RuntimeException toThrow = failure;
                failure = null;
                throw toThrow;
            }
            return jobs;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(LocalDateTime start) {
            this.instant = start.toInstant(ZoneOffset.UTC);
        }

        void advance(LocalDateTime to) {
            this.instant = to.toInstant(ZoneOffset.UTC);
        }

        void advanceHours(long hours) {
            this.instant = instant.plusSeconds(hours * 3600);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
