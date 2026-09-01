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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
        service = new JobIngestServiceImpl(
                List.of(greenhouse),
                jobSourceMapper,
                jobSourceRecordMapper,
                jobCatalogMapper,
                jobIngestRunMapper,
                properties,
                clock);
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
    void identicalPostingsWithoutAUrlMatchAreMergedOnFingerprint() {
        JobSource acme = source("GREENHOUSE", "acme");
        JobSource mirror = source("GREENHOUSE", "acme-mirror");

        greenhouse.jobs = List.of(job("gh-1", "Java Developer", "Acme", "Toronto, ON",
                "https://boards.greenhouse.io/acme/jobs/1"));
        service.collectSource(acme);

        greenhouse.jobs = List.of(job("mr-1", "java developer", "ACME", "toronto, on",
                "https://boards.greenhouse.io/acme-mirror/jobs/77"));
        service.collectSource(mirror);

        assertEquals(1, countCatalog());
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
