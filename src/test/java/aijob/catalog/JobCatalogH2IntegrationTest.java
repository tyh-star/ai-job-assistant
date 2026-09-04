package aijob.catalog;

import aijob.AiJobApplication;
import aijob.dto.JobCatalogDetail;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.JobCatalogSummary;
import aijob.dto.SaveCatalogJobResponse;
import aijob.entity.Job;
import aijob.entity.JobCatalogEntry;
import aijob.enums.CatalogJobStatus;
import aijob.enums.EmploymentType;
import aijob.enums.RemoteType;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobNotFoundException;
import aijob.mapper.JobCatalogMapper;
import aijob.mapper.JobMapper;
import aijob.service.JobCatalogService;
import aijob.util.CatalogFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the catalog search and save paths against a real database. The tables
 * come from the same DDL as production, so index-backed filters, the unique keys and
 * the save transaction are all covered rather than mocked away.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jobcatalog;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads",
                // This suite exercises the LOCAL catalog implementation specifically.
                // Without this, the default provider (freehire) would be autowired and
                // every assertion here -- built against rows this test seeded into
                // job_catalog -- would run against the wrong service entirely.
                "aijob.job-catalog.provider=local_catalog"
        }
)
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class JobCatalogH2IntegrationTest {

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 12, 0);

    @Autowired
    private JobCatalogService jobCatalogService;

    @Autowired
    private JobCatalogMapper jobCatalogMapper;

    @Autowired
    private JobMapper jobMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedUsers() {
        insertUser(ALICE, "Alice");
        insertUser(BOB, "Bob");
    }

    @Test
    void searchReturnsOnlyActivePostings() {
        insertCatalogJob("Java Developer", "Acme", "CA", RemoteType.HYBRID, CatalogJobStatus.ACTIVE);
        insertCatalogJob("Retired Role", "Acme", "CA", RemoteType.HYBRID, CatalogJobStatus.EXPIRED);
        insertCatalogJob("Deleted Role", "Acme", "CA", RemoteType.HYBRID, CatalogJobStatus.REMOVED);

        JobCatalogSearchResponse response = search(request(null, null, null, null, null, null, null, null, null));

        assertEquals(1, response.totalCount());
        assertEquals("Java Developer", response.results().get(0).title());
    }

    @Test
    void keywordLocationCountryAndRemoteFiltersNarrowResults() {
        insertCatalogJob("Java Developer", "Acme", "Toronto, ON", "CA", RemoteType.HYBRID,
                EmploymentType.FULL_TIME, CatalogJobStatus.ACTIVE, NOW.minusDays(1), "Spring Boot services.");
        insertCatalogJob("Python Developer", "Globex", "Berlin", "DE", RemoteType.REMOTE,
                EmploymentType.CONTRACT, CatalogJobStatus.ACTIVE, NOW.minusDays(1), "Django services.");

        assertEquals(1, search(request("java", null, null, null, null, null, null, null, null)).totalCount());
        assertEquals(1, search(request(null, "toronto", null, null, null, null, null, null, null)).totalCount());
        assertEquals(1, search(request(null, null, "DE", null, null, null, null, null, null)).totalCount());
        assertEquals(1, search(request(null, null, null, "REMOTE", null, null, null, null, null)).totalCount());
        assertEquals(1, search(request(null, null, null, null, "CONTRACT", null, null, null, null)).totalCount());
        // Keyword also reaches the description, not just the title.
        assertEquals(1, search(request("django", null, null, null, null, null, null, null, null)).totalCount());
    }

    @Test
    void postedWithinDaysExcludesOlderPostings() {
        insertCatalogJob("Recent Role", "Acme", "Toronto", "CA", RemoteType.ONSITE,
                EmploymentType.FULL_TIME, CatalogJobStatus.ACTIVE, LocalDateTime.now().minusDays(2), "Recent.");
        insertCatalogJob("Old Role", "Acme", "Toronto", "CA", RemoteType.ONSITE,
                EmploymentType.FULL_TIME, CatalogJobStatus.ACTIVE, LocalDateTime.now().minusDays(60), "Old.");

        JobCatalogSearchResponse response =
                search(request(null, null, null, null, null, 7, null, null, null));

        assertEquals(1, response.totalCount());
        assertEquals("Recent Role", response.results().get(0).title());
    }

    @Test
    void pagingIsServerSideAndPageSizeIsCapped() {
        for (int i = 0; i < 5; i++) {
            insertCatalogJob("Role " + i, "Acme", "Toronto", "CA", RemoteType.ONSITE,
                    EmploymentType.FULL_TIME, CatalogJobStatus.ACTIVE, NOW.minusDays(i), "Body " + i);
        }

        JobCatalogSearchResponse first = search(request(null, null, null, null, null, null, 1, 2, null));
        assertEquals(2, first.results().size());
        assertEquals(5, first.totalCount());
        assertEquals(3, first.totalPages());
        assertTrue(first.hasMore());

        JobCatalogSearchResponse last = search(request(null, null, null, null, null, null, 3, 2, null));
        assertEquals(1, last.results().size());
        assertFalse(last.hasMore());

        JobCatalogSearchResponse oversized =
                search(request(null, null, null, null, null, null, 1, 10_000, null));
        assertEquals(JobCatalogSearchRequest.MAX_PAGE_SIZE, oversized.size());
    }

    @Test
    void invalidFilterValuesAreRejected() {
        assertThrows(InvalidJobSearchException.class,
                () -> search(request(null, null, "CANADA", null, null, null, null, null, null)));
        assertThrows(InvalidJobSearchException.class,
                () -> search(request(null, null, null, "ANYWHERE", null, null, null, null, null)));
        assertThrows(InvalidJobSearchException.class,
                () -> search(request(null, null, null, null, null, 9, null, null, null)));
        assertThrows(InvalidJobSearchException.class,
                () -> search(request(null, null, null, null, null, null, 0, null, null)));
    }

    @Test
    void detailSanitizesHtmlAndNeverExposesAScript() {
        Long id = insertCatalogJobWithHtml(
                "<p>Great role</p><script>steal()</script><a href=\"javascript:alert(1)\">x</a>");

        JobCatalogDetail detail = jobCatalogService.findDetail(ALICE, String.valueOf(id));

        assertNotNull(detail.descriptionHtml());
        assertTrue(detail.descriptionHtml().contains("<p>Great role</p>"));
        assertFalse(detail.descriptionHtml().contains("<script"));
        assertFalse(detail.descriptionHtml().toLowerCase().contains("javascript:"));
    }

    @Test
    void missingCatalogJobIsNotFound() {
        assertThrows(JobNotFoundException.class, () -> jobCatalogService.findDetail(ALICE, "9999"));
    }

    @Test
    void aNonNumericCatalogJobIdIsNotFoundRatherThanAServerError() {
        // A reference from the OTHER provider (e.g. a freehire:<slug> id, sent while
        // this deployment is running local_catalog) must not blow up as a parse error --
        // from the caller's side, this provider genuinely has no such row.
        assertThrows(JobNotFoundException.class,
                () -> jobCatalogService.findDetail(ALICE, "freehire:some-slug"));
        assertThrows(JobNotFoundException.class,
                () -> jobCatalogService.saveToMyJobs(ALICE, "not-a-number"));
    }

    @Test
    void saveCreatesAPersonalJobAndIsIdempotentPerUser() {
        Long catalogId = insertCatalogJob("Java Developer", "Acme", "CA", RemoteType.HYBRID,
                CatalogJobStatus.ACTIVE);

        SaveCatalogJobResponse first = jobCatalogService.saveToMyJobs(ALICE, String.valueOf(catalogId));
        assertFalse(first.alreadySaved());
        assertNotNull(first.jobId());

        SaveCatalogJobResponse second = jobCatalogService.saveToMyJobs(ALICE, String.valueOf(catalogId));
        assertTrue(second.alreadySaved());
        assertEquals(first.jobId(), second.jobId());

        assertEquals(1, countJobsFor(ALICE));
        assertEquals(1, countMappingsFor(ALICE));

        Job personal = jobMapper.findById(first.jobId());
        assertEquals("Acme", personal.getCompany());
        assertEquals("Java Developer", personal.getTitle());
        assertEquals("JOB_CATALOG", personal.getSource());
        assertEquals(String.valueOf(catalogId), personal.getExternalJobId());
    }

    @Test
    void twoUsersEachGetTheirOwnPersonalJob() {
        Long catalogId = insertCatalogJob("Java Developer", "Acme", "CA", RemoteType.HYBRID,
                CatalogJobStatus.ACTIVE);

        SaveCatalogJobResponse forAlice = jobCatalogService.saveToMyJobs(ALICE, String.valueOf(catalogId));
        SaveCatalogJobResponse forBob = jobCatalogService.saveToMyJobs(BOB, String.valueOf(catalogId));

        assertNotNull(forAlice.jobId());
        assertNotNull(forBob.jobId());
        assertTrue(!forAlice.jobId().equals(forBob.jobId()));
        assertEquals(userId(ALICE), jobMapper.findById(forAlice.jobId()).getUserId());
        assertEquals(userId(BOB), jobMapper.findById(forBob.jobId()).getUserId());
    }

    @Test
    void aUserCannotSeeAnotherUsersPersonalJob() {
        Long catalogId = insertCatalogJob("Java Developer", "Acme", "CA", RemoteType.HYBRID,
                CatalogJobStatus.ACTIVE);
        SaveCatalogJobResponse forAlice = jobCatalogService.saveToMyJobs(ALICE, String.valueOf(catalogId));

        assertTrue(jobMapper.findByUserId(userId(ALICE)).stream()
                .anyMatch(job -> job.getId().equals(forAlice.jobId())));
        assertTrue(jobMapper.findByUserId(userId(BOB)).isEmpty());
        assertEquals(0, jobMapper.deleteByIdAndUserId(forAlice.jobId(), userId(BOB)));
    }

    @Test
    void savedPersonalJobIsASnapshotAndDoesNotFollowLaterCatalogEdits() {
        Long catalogId = insertCatalogJob("Java Developer", "Acme", "CA", RemoteType.HYBRID,
                CatalogJobStatus.ACTIVE);
        SaveCatalogJobResponse saved = jobCatalogService.saveToMyJobs(ALICE, String.valueOf(catalogId));
        String originalDescription = jobMapper.findById(saved.jobId()).getDescription();

        JobCatalogEntry entry = jobCatalogMapper.findById(catalogId);
        entry.setTitle("Rewritten Title");
        entry.setDescriptionText("Completely different body.");
        jobCatalogMapper.update(entry);

        Job personal = jobMapper.findById(saved.jobId());
        assertEquals("Java Developer", personal.getTitle());
        assertEquals(originalDescription, personal.getDescription());
    }

    @Test
    void anExpiredPostingStaysSavableButARemovedOneDoesNot() {
        Long expired = insertCatalogJob("Expired Role", "Acme", "CA", RemoteType.HYBRID,
                CatalogJobStatus.EXPIRED);
        Long removed = insertCatalogJob("Removed Role", "Acme", "CA", RemoteType.HYBRID,
                CatalogJobStatus.REMOVED);

        assertNotNull(jobCatalogService.saveToMyJobs(ALICE, String.valueOf(expired)).jobId());
        assertThrows(JobNotFoundException.class, () -> jobCatalogService.saveToMyJobs(ALICE, String.valueOf(removed)));
    }

    @Test
    void searchMarksPostingsTheCallerAlreadySaved() {
        Long catalogId = insertCatalogJob("Java Developer", "Acme", "CA", RemoteType.HYBRID,
                CatalogJobStatus.ACTIVE);
        SaveCatalogJobResponse saved = jobCatalogService.saveToMyJobs(ALICE, String.valueOf(catalogId));

        JobCatalogSummary forAlice = search(ALICE).results().get(0);
        assertTrue(forAlice.saved());
        assertEquals(saved.jobId(), forAlice.personalJobId());

        JobCatalogSummary forBob = search(BOB).results().get(0);
        assertFalse(forBob.saved());
        assertNull(forBob.personalJobId());
    }

    @Test
    void searchResultsCarrySourceAttributionAndNoRawPayload() {
        Long sourceId = insertJobSource("GREENHOUSE", "acme");
        Long catalogId = insertCatalogJob("Java Developer", "Acme", "CA", RemoteType.HYBRID,
                CatalogJobStatus.ACTIVE);
        insertSourceRecord(sourceId, "gh-1", catalogId, "{\"secret\":\"do-not-return\"}");

        JobCatalogSummary summary = search(ALICE).results().get(0);

        assertEquals("GREENHOUSE", summary.source());
        assertNotNull(summary.applyUrl());
        assertNotNull(summary.firstSeenAt());
        assertFalse(summary.toString().contains("do-not-return"));
    }

    private JobCatalogSearchResponse search(String email) {
        return jobCatalogService.search(email,
                request(null, null, null, null, null, null, null, null, null));
    }

    private JobCatalogSearchResponse search(JobCatalogSearchRequest request) {
        return jobCatalogService.search(ALICE, request);
    }

    private static JobCatalogSearchRequest request(String q, String location, String country,
                                                   String remoteType, String employmentType,
                                                   Integer postedWithinDays, Integer page,
                                                   Integer size, String sort) {
        return new JobCatalogSearchRequest(
                q, location, country, remoteType, employmentType, postedWithinDays, page, size, sort);
    }

    private void insertUser(String email, String name) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, "not-a-real-hash", name);
    }

    private Long userId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private int countJobsFor(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE user_id = ?", Integer.class, userId(email));
    }

    private int countMappingsFor(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_saved_catalog_jobs WHERE user_id = ?",
                Integer.class, userId(email));
    }

    private Long insertCatalogJob(String title, String company, String country,
                                  RemoteType remoteType, CatalogJobStatus status) {
        return insertCatalogJob(title, company, "Toronto, ON", country, remoteType,
                EmploymentType.FULL_TIME, status, NOW.minusDays(1), "Body of " + title);
    }

    private Long insertCatalogJob(String title, String company, String location, String country,
                                  RemoteType remoteType, EmploymentType employmentType,
                                  CatalogJobStatus status, LocalDateTime postedAt, String body) {
        JobCatalogEntry entry = new JobCatalogEntry();
        entry.setTitle(title);
        entry.setCompanyName(company);
        entry.setDescriptionText(body);
        entry.setDescriptionHtml("<p>" + body + "</p>");
        entry.setLocationText(location);
        entry.setCountryCode(country);
        entry.setRemoteType(remoteType.name());
        entry.setEmploymentType(employmentType.name());
        entry.setCanonicalUrl("https://boards.greenhouse.io/acme/jobs/" + title.hashCode());
        entry.setApplyUrl("https://boards.greenhouse.io/acme/jobs/" + title.hashCode());
        entry.setCanonicalUrlNorm(CatalogFingerprint.normalizeUrl(entry.getCanonicalUrl()));
        entry.setApplyUrlNorm(CatalogFingerprint.normalizeUrl(entry.getApplyUrl()));
        entry.setPostedAt(postedAt);
        entry.setFirstSeenAt(postedAt);
        entry.setLastSeenAt(postedAt);
        entry.setStatus(status.name());
        entry.setFingerprint(CatalogFingerprint.of(company, title, location));
        jobCatalogMapper.insert(entry);
        return entry.getId();
    }

    private Long insertCatalogJobWithHtml(String html) {
        JobCatalogEntry entry = new JobCatalogEntry();
        entry.setTitle("Java Developer");
        entry.setCompanyName("Acme");
        entry.setDescriptionText("Great role");
        entry.setDescriptionHtml(html);
        entry.setLocationText("Toronto, ON");
        entry.setCountryCode("CA");
        entry.setRemoteType(RemoteType.HYBRID.name());
        entry.setEmploymentType(EmploymentType.FULL_TIME.name());
        entry.setPostedAt(NOW.minusDays(1));
        entry.setFirstSeenAt(NOW.minusDays(1));
        entry.setLastSeenAt(NOW.minusDays(1));
        entry.setStatus(CatalogJobStatus.ACTIVE.name());
        entry.setFingerprint(CatalogFingerprint.of("Acme", "Java Developer", "Toronto, ON"));
        jobCatalogMapper.insert(entry);
        return entry.getId();
    }

    private Long insertJobSource(String providerType, String tenantKey) {
        jdbcTemplate.update("""
                INSERT INTO job_sources (provider_type, display_name, tenant_key, enabled, crawl_allowed)
                VALUES (?, ?, ?, 1, 1)
                """, providerType, tenantKey, tenantKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM job_sources WHERE provider_type = ? AND tenant_key = ?",
                Long.class, providerType, tenantKey);
    }

    private void insertSourceRecord(Long sourceId, String externalId, Long catalogJobId, String payload) {
        jdbcTemplate.update("""
                INSERT INTO job_source_records
                    (source_id, external_job_id, catalog_job_id, payload_hash, raw_payload, active)
                VALUES (?, ?, ?, ?, ?, 1)
                """, sourceId, externalId, catalogJobId, CatalogFingerprint.sha256(payload), payload);
    }

    @Test
    void catalogSummaryListNeverLeaksTheStoredPayloadColumn() {
        Long sourceId = insertJobSource("LEVER", "globex");
        Long catalogId = insertCatalogJob("Java Developer", "Acme", "CA", RemoteType.HYBRID,
                CatalogJobStatus.ACTIVE);
        insertSourceRecord(sourceId, "lv-1", catalogId, "{\"raw\":\"payload-marker\"}");

        List<JobCatalogSummary> results = search(ALICE).results();

        assertEquals(1, results.size());
        assertFalse(results.get(0).toString().contains("payload-marker"));
    }
}
