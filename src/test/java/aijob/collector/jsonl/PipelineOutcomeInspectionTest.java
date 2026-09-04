package aijob.collector.jsonl;

import aijob.AiJobApplication;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.JobCatalogSummary;
import aijob.service.JobCatalogService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads back what a pipeline run actually left in the database, through the same search
 * the website uses.
 *
 * <p>Read-only and additive: it inserts one user to search as, and asserts over rows the
 * pipeline wrote. It deliberately does NOT build the schema or truncate — the point is to
 * inspect the state a real run produced, which a suite that resets first would destroy.
 * That also means it must run AFTER a pipeline run, not as part of the regression suite;
 * it skips unless the MySQL variables are present.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=${AIJOB_MYSQL_TEST_URL}",
                "spring.datasource.username=${AIJOB_MYSQL_TEST_USERNAME}",
                "spring.datasource.password=${AIJOB_MYSQL_TEST_PASSWORD}",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads",
                "aijob.collector.allowlist-file=config/sources-allowlist.json",
                "aijob.job-catalog.provider=local_catalog"
        }
)
@EnabledIfEnvironmentVariable(named = "AIJOB_MYSQL_TEST_URL", matches = ".+",
        disabledReason = "inspects the state a pipeline run left; needs the test database")
class PipelineOutcomeInspectionTest {

    private static final Logger log = LoggerFactory.getLogger(PipelineOutcomeInspectionTest.class);
    private static final String INSPECTOR = "pipeline-inspector@example.com";

    @Autowired
    private JobCatalogService jobCatalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ensureInspectorExists() {
        Integer sources = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_sources WHERE provider_type = 'GREENHOUSE' AND tenant_key = 'vercel'",
                Integer.class);
        Assumptions.assumeTrue(sources != null && sources > 0,
                "no greenhouse/vercel source yet -- run the pipeline first");

        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, INSPECTOR);
        if (existing == null || existing == 0) {
            jdbcTemplate.update(
                    "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                    INSPECTOR, "not-a-real-hash", "Pipeline Inspector");
        }
    }

    @Test
    void whatThePipelineLeftIsSearchableAndNothingWasExpired() {
        Long sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM job_sources WHERE provider_type = 'GREENHOUSE' AND tenant_key = 'vercel'",
                Long.class);

        int records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_source_records WHERE source_id = ?", Integer.class, sourceId);
        int active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_source_records WHERE source_id = ? AND active = 1",
                Integer.class, sourceId);
        int expiredCatalog = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_catalog WHERE status <> 'ACTIVE'", Integer.class);

        // A sample import has no expiry path at all, so every posting any run has ever
        // carried is still live -- including the ones the latest 50-of-87 batch omitted.
        assertEquals(records, active, "a sample import must not deactivate a source record");
        assertEquals(0, expiredCatalog, "no posting should have been expired or closed");

        // Every ingest run for this source is a SAMPLE_IMPORT: none claimed SUCCESS, which
        // is what would license an expiry sweep.
        List<Map<String, Object>> runs = jdbcTemplate.queryForList("""
                SELECT status, fetched_count, inserted_count, updated_count, unchanged_count,
                       expired_count, failed_count, error_summary
                  FROM job_ingest_runs
                 WHERE source_id = ?
                 ORDER BY id DESC
                 LIMIT 5
                """, sourceId);
        assertTrue(runs.size() > 0, "the pipeline should have recorded a run");
        for (Map<String, Object> run : runs) {
            assertEquals("SAMPLE_IMPORT", run.get("status"));
            assertEquals(0, ((Number) run.get("expired_count")).intValue());
            // The export's run id is carried on the ingest run, so a catalog row can be
            // traced back to the batch that produced it without a new table.
            assertNotNull(run.get("error_summary"));
            assertTrue(String.valueOf(run.get("error_summary")).startsWith("IMPORT run="),
                    "the ingest run should name the export batch: " + run.get("error_summary"));
        }

        // And the website's own search finds them.
        JobCatalogSearchResponse byCompany = search("Vercel");
        JobCatalogSearchResponse byTitle = search("Engineer");
        assertTrue(byCompany.totalCount() > 0, "the imported postings must be searchable");

        log.info("PIPELINE-STATE source_id={} source_records={} active={} expired_catalog_rows={}",
                sourceId, records, active, expiredCatalog);
        log.info("PIPELINE-STATE ingest runs (newest first):");
        for (Map<String, Object> run : runs) {
            log.info("PIPELINE-STATE   status={} fetched={} ins={} upd={} unch={} expired={} failed={} | {}",
                    run.get("status"), run.get("fetched_count"), run.get("inserted_count"),
                    run.get("updated_count"), run.get("unchanged_count"), run.get("expired_count"),
                    run.get("failed_count"), run.get("error_summary"));
        }
        log.info("PIPELINE-STATE search q=Vercel total={} q=Engineer total={}",
                byCompany.totalCount(), byTitle.totalCount());
        for (JobCatalogSummary s : byCompany.results().stream().limit(3).toList()) {
            log.info("PIPELINE-STATE   hit: {} | {} | {}", s.title(), s.companyName(), s.applyUrl());
        }
    }

    private JobCatalogSearchResponse search(String q) {
        return jobCatalogService.search(INSPECTOR,
                new JobCatalogSearchRequest(q, null, null, null, null, null, 1, 50, null));
    }
}
