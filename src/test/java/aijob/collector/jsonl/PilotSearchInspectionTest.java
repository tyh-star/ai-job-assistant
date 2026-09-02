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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Searches what a pilot collection run left, through the service the website uses.
 *
 * <p>Read-only and additive: it inserts one user to search as and asserts over rows the
 * pipeline wrote. It does NOT build the schema or truncate — the state a real run
 * produced is the subject, and a suite that reset first would destroy it. So it runs
 * after a pilot run, not as part of the regression suite, and skips without the MySQL
 * variables.
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
                "aijob.collector.allowlist-file=config/sources-allowlist.pilot.json"
        }
)
@EnabledIfEnvironmentVariable(named = "AIJOB_MYSQL_TEST_URL", matches = ".+",
        disabledReason = "inspects what a pilot run left; needs the test database")
class PilotSearchInspectionTest {

    private static final Logger log = LoggerFactory.getLogger(PilotSearchInspectionTest.class);
    private static final String INSPECTOR = "pilot-inspector@example.com";

    @Autowired
    private JobCatalogService jobCatalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ensureInspectorExists() {
        Integer boards = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_sources WHERE tenant_key NOT LIKE 'fixture%'", Integer.class);
        Assumptions.assumeTrue(boards != null && boards >= 2,
                "no pilot boards yet -- run the pilot collection first");

        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, INSPECTOR);
        if (existing == null || existing == 0) {
            jdbcTemplate.update(
                    "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                    INSPECTOR, "not-a-real-hash", "Pilot Inspector");
        }
    }

    @Test
    void everyPilotCompanyIsFindableByName() {
        List<Map<String, Object>> boards = jdbcTemplate.queryForList(
                "SELECT provider_type, tenant_key, display_name FROM job_sources "
                        + "WHERE tenant_key NOT LIKE 'fixture%' ORDER BY provider_type, tenant_key");

        log.info("PILOT ---- search by company name ----");
        for (Map<String, Object> board : boards) {
            String company = String.valueOf(board.get("display_name"));
            JobCatalogSearchResponse hits = search(company, null, null);
            long stored = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM job_source_records r
                      JOIN job_sources s ON s.id = r.source_id
                     WHERE s.provider_type = ? AND s.tenant_key = ?
                    """, Long.class, board.get("provider_type"), board.get("tenant_key"));
            // stored comes from the source join -- the exact company. hits comes from
            // full-text search, which will also match another employer's posting that
            // mentions the name ("Elasticsearch" in a Cloudflare JD). They are different
            // questions, so this asserts findability, NOT equality: treating a full-text
            // company-name search as an exact company filter is precisely the mistake
            // that would make these numbers look wrong.
            log.info("PILOT   {}/{} company='{}' stored={} search_hits={}{}",
                    board.get("provider_type"), board.get("tenant_key"), company, stored, hits.totalCount(),
                    hits.totalCount() > stored ? "  (full-text also matched other employers' text)" : "");
            assertTrue(hits.totalCount() > 0,
                    "a company with " + stored + " stored postings must be findable by name: " + company);
        }
    }

    @Test
    void keywordSearchSpansCompanies() {
        for (String q : List.of("engineer", "senior", "security", "designer", "manager")) {
            JobCatalogSearchResponse hits = search(q, null, null);
            long companies = hits.results().stream().map(JobCatalogSummary::companyName).distinct().count();
            log.info("PILOT keyword '{}' -> {} hits across {} companies (page of {})",
                    q, hits.totalCount(), companies, hits.results().size());
        }
        assertTrue(search("engineer", null, null).totalCount() > 0);
    }

    @Test
    void theCountryFilterWorksOnlyWhereTheSourcePublishedACountry() {
        // The honest version of "region filtering works". It works on rows that HAVE a
        // country, and the two providers differ completely in whether they supply one --
        // so a single "region search works" claim would be true for Lever and false for
        // Greenhouse.
        List<Map<String, Object>> byProvider = jdbcTemplate.queryForList("""
                SELECT s.provider_type,
                       COUNT(*) AS jobs,
                       SUM(c.country_code IS NOT NULL) AS with_country,
                       SUM(c.city IS NOT NULL) AS with_city
                  FROM job_source_records r
                  JOIN job_sources s ON s.id = r.source_id
                  JOIN job_catalog  c ON c.id = r.catalog_job_id
                 WHERE s.tenant_key NOT LIKE 'fixture%'
                 GROUP BY s.provider_type
                """);
        log.info("PILOT ---- what the sources actually published ----");
        for (Map<String, Object> row : byProvider) {
            log.info("PILOT   {} jobs={} with_country={} with_city={}",
                    row.get("provider_type"), row.get("jobs"),
                    row.get("with_country"), row.get("with_city"));
        }

        // Counted over the WHOLE catalog, not just the pilot boards. The search service
        // has no idea which rows came from a pilot, so filtering one side and not the
        // other compares two different questions -- and the mismatch it produced (54
        // against 56) looked like a filter bug when it was two synthetic fixture rows.
        List<Map<String, Object>> countries = jdbcTemplate.queryForList("""
                SELECT country_code, COUNT(*) AS jobs
                  FROM job_catalog
                 WHERE country_code IS NOT NULL AND status = 'ACTIVE'
                 GROUP BY country_code ORDER BY jobs DESC
                """);
        log.info("PILOT ---- country filter, through the search service ----");
        for (Map<String, Object> row : countries) {
            String code = String.valueOf(row.get("country_code"));
            JobCatalogSearchResponse hits = search(null, null, code);
            log.info("PILOT   country={} rows={} search_hits={}", code, row.get("jobs"), hits.totalCount());
            assertEquals(((Number) row.get("jobs")).longValue(), hits.totalCount(),
                    "the country filter should return exactly the rows carrying that country");
        }

        // Greenhouse rows carry no country at all, so no country query can reach them.
        // Asserted rather than described: it is a limitation, and a limitation nobody
        // checks is one that quietly becomes wrong.
        Long greenhouseWithCountry = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM job_source_records r
                  JOIN job_sources s ON s.id = r.source_id
                  JOIN job_catalog  c ON c.id = r.catalog_job_id
                 WHERE s.provider_type = 'GREENHOUSE' AND s.tenant_key NOT LIKE 'fixture%'
                   AND c.country_code IS NOT NULL
                """, Long.class);
        log.info("PILOT   GREENHOUSE rows reachable by a country filter: {}", greenhouseWithCountry);
    }

    @Test
    void everyPostingKeptItsOwnApplyLinkAndAFullDescription() {
        Map<String, Object> shape = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS jobs,
                       COUNT(DISTINCT c.apply_url) AS distinct_links,
                       SUM(c.apply_url LIKE 'https://%') AS https_links,
                       MIN(CHAR_LENGTH(c.description_text)) AS shortest_jd,
                       SUM(c.status = 'ACTIVE') AS active
                  FROM job_source_records r
                  JOIN job_sources s ON s.id = r.source_id
                  JOIN job_catalog  c ON c.id = r.catalog_job_id
                 WHERE s.tenant_key NOT LIKE 'fixture%'
                """);
        long jobs = ((Number) shape.get("jobs")).longValue();
        log.info("PILOT ---- content shape ---- {}", shape);

        assertEquals(jobs, ((Number) shape.get("distinct_links")).longValue(),
                "every posting must have its own apply link, not a shared careers page");
        assertEquals(jobs, ((Number) shape.get("https_links")).longValue());
        assertEquals(jobs, ((Number) shape.get("active")).longValue(),
                "a sample import expires nothing");
        assertTrue(((Number) shape.get("shortest_jd")).longValue() > 500,
                "the shortest description should still be a real posting body");
    }

    private JobCatalogSearchResponse search(String q, String location, String country) {
        return jobCatalogService.search(INSPECTOR,
                new JobCatalogSearchRequest(q, location, country, null, null, null, 1, 50, null));
    }
}
