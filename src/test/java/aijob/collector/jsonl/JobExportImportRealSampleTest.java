package aijob.collector.jsonl;

import aijob.AiJobApplication;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.entity.JobCatalogEntry;
import aijob.enums.CatalogJobStatus;
import aijob.mapper.JobCatalogMapper;
import aijob.mapper.JobSourceMapper;
import aijob.service.JobCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the importer over a real export, on demand.
 *
 * <p>This is an operator check, not part of the regression suite: it needs a collected
 * file, and collected job data is neither committed nor a dependency of the build. It
 * skips unless it is asked for AND the files are actually there:
 *
 * <pre>
 * mvn test -Dtest=JobExportImportRealSampleTest \
 *   -Daijob.import.sample.dir="C:/ai job/freehire-export" \
 *   -Daijob.import.sample.name=vercel-greenhouse
 * </pre>
 *
 * <p>The regression coverage for every behaviour asserted here lives in
 * {@link JobExportImportH2IntegrationTest}, on generated fixtures, so nothing is lost
 * when this skips.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jobexportreal;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads",
                "aijob.collector.allowlist-file=src/test/resources/config/test-sources-allowlist.json",
                "aijob.job-catalog.provider=local_catalog"
        }
)
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class JobExportImportRealSampleTest {

    private static final String DIR_PROPERTY = "aijob.import.sample.dir";
    private static final String NAME_PROPERTY = "aijob.import.sample.name";
    private static final String ALICE = "alice@example.com";

    private static final Logger log = LoggerFactory.getLogger(JobExportImportRealSampleTest.class);

    @Autowired
    private JobExportImporter importer;

    @Autowired
    private JobCatalogService jobCatalogService;

    @Autowired
    private JobCatalogMapper jobCatalogMapper;

    @Autowired
    private JobSourceMapper jobSourceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Path jsonl;
    private Path manifest;

    @BeforeEach
    void setUp() {
        String dir = System.getProperty(DIR_PROPERTY);
        Assumptions.assumeTrue(dir != null && !dir.isBlank(),
                "set -D" + DIR_PROPERTY + " to run the real-sample check");
        String name = System.getProperty(NAME_PROPERTY, "vercel-greenhouse");
        jsonl = Path.of(dir, name + ".jsonl");
        manifest = Path.of(dir, name + ".manifest.json");
        Assumptions.assumeTrue(Files.isRegularFile(jsonl) && Files.isRegularFile(manifest),
                "no export at " + jsonl);

        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                ALICE, "not-a-real-hash", "Alice");
    }

    @Test
    void theRealSampleImportsAndStaysASample() throws Exception {
        long lines = Files.readAllLines(jsonl).stream().filter(l -> !l.isBlank()).count();

        JobExportImportSummary first = importer.importBatch(jsonl, manifest);

        assertEquals(lines, first.recordsRead(), "every line in the file was read");
        assertEquals(lines, first.inserted());
        assertEquals(0, first.failed());
        assertEquals(0, first.expired());
        assertFalse(first.completeSnapshot(),
                "the exporter capped this batch, so it is a sample of the board");
        assertEquals(lines, countCatalog());

        // Re-importing the identical file must not duplicate anything.
        JobExportImportSummary second = importer.importBatch(jsonl, manifest);
        assertEquals(0, second.inserted());
        assertEquals(lines, second.unchanged());
        assertEquals(lines, countCatalog());

        // Every posting is live, has its link, and kept a real description.
        List<JobCatalogEntry> entries = jdbcTemplate.queryForList(
                        "SELECT id FROM job_catalog ORDER BY id", Long.class)
                .stream().map(jobCatalogMapper::findById).toList();
        for (JobCatalogEntry entry : entries) {
            assertEquals(CatalogJobStatus.ACTIVE.name(), entry.getStatus());
            assertNotNull(entry.getApplyUrl());
            assertTrue(entry.getApplyUrl().startsWith("https://"));
            assertTrue(entry.getDescriptionText().length() > 500,
                    "a real posting body should survive whole, got " + entry.getDescriptionText().length());
            // Greenhouse publishes no posting date; the importer stores none.
            assertNull(entry.getPostedAt());
        }

        // The board is registered but not switched on for crawling.
        assertFalse(jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "vercel").isEnabled());

        // And the catalog search finds them.
        JobCatalogSearchResponse byCompany = jobCatalogService.search(ALICE, new JobCatalogSearchRequest(
                "Vercel", null, null, null, null, null, 1, 50, null));
        assertTrue(byCompany.totalCount() > 0, "the imported postings are searchable");
        JobCatalogSearchResponse byTitle = jobCatalogService.search(ALICE, new JobCatalogSearchRequest(
                "Engineer", null, null, null, null, null, 1, 50, null));

        // Printed so an operator running this check sees the numbers rather than only a
        // green tick. The assertions above are what actually gates the run.
        log.info("REAL-SAMPLE run={} board={}/{}", first.runId(), first.provider(), first.board());
        log.info("REAL-SAMPLE read={} inserted={} updated={} unchanged={} failed={} expired={} complete_snapshot={}",
                first.recordsRead(), first.inserted(), first.updated(), first.unchanged(),
                first.failed(), first.expired(), first.completeSnapshot());
        log.info("REAL-SAMPLE reimport inserted={} updated={} unchanged={} catalog_rows={}",
                second.inserted(), second.updated(), second.unchanged(), countCatalog());
        log.info("REAL-SAMPLE search q=Vercel total={} q=Engineer total={}",
                byCompany.totalCount(), byTitle.totalCount());
        log.info("REAL-SAMPLE active={} of {} catalog rows", activeCount(), countCatalog());
    }

    private int activeCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_catalog WHERE status = 'ACTIVE'", Integer.class);
    }

    private int countCatalog() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_catalog", Integer.class);
    }
}
