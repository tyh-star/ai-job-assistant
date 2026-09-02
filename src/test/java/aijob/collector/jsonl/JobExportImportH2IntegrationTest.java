package aijob.collector.jsonl;

import aijob.AiJobApplication;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.JobCatalogSummary;
import aijob.entity.JobCatalogEntry;
import aijob.entity.JobIngestRun;
import aijob.entity.JobSource;
import aijob.entity.JobSourceRecord;
import aijob.enums.CatalogJobStatus;
import aijob.enums.IngestRunStatus;
import aijob.enums.RemoteType;
import aijob.mapper.JobCatalogMapper;
import aijob.mapper.JobSourceMapper;
import aijob.mapper.JobSourceRecordMapper;
import aijob.service.JobCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSONL import path against a real database.
 *
 * <p>Every batch here is generated into a temp directory by the test itself, so the
 * suite depends on no machine's file layout and carries no collected job data. The
 * separate {@code JobExportImportRealSampleTest} runs the same importer over an actual
 * export when one is present and explicitly asked for.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jobexportimport;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads",
                "aijob.collector.allowlist-file=src/test/resources/config/test-sources-allowlist.json"
        }
)
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class JobExportImportH2IntegrationTest {

    private static final String ALICE = "alice@example.com";
    private static final String RUN = "20260901T161700Z-f17ef6d96646";

    @Autowired
    private JobExportImporter importer;

    @Autowired
    private JobCatalogService jobCatalogService;

    @Autowired
    private JobCatalogMapper jobCatalogMapper;

    @Autowired
    private JobSourceMapper jobSourceMapper;

    @Autowired
    private JobSourceRecordMapper jobSourceRecordMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path batchDir;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                ALICE, "not-a-real-hash", "Alice");
    }

    // ---------------------------------------------------------------------------
    // 1. a full batch imports
    // ---------------------------------------------------------------------------

    @Test
    void aFiftyRecordBatchImportsIntoAnEmptyCatalog() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 50);

        JobExportImportSummary summary = importer.importBatch(batch.jsonl(), batch.manifest());

        assertEquals(50, summary.recordsRead());
        assertEquals(50, summary.inserted());
        assertEquals(0, summary.updated());
        assertEquals(0, summary.failed());
        assertEquals(50, countCatalog());
        assertEquals(50, countSourceRecords());
    }

    @Test
    void theSourceRowIsCreatedForTheProviderAndBoardAndStaysDisabled() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 3);

        importer.importBatch(batch.jsonl(), batch.manifest());

        JobSource source = jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "vercel");
        assertNotNull(source);
        // Importing a file exported from a board is not a decision to start crawling it.
        assertFalse(source.isEnabled());
        assertFalse(source.isCrawlAllowed());
        // And the source's crawl state is untouched: no successful full read happened.
        assertNull(source.getLastSuccessAt());
    }

    // ---------------------------------------------------------------------------
    // 2. re-importing the same file changes nothing
    // ---------------------------------------------------------------------------

    @Test
    void reimportingTheSameBatchAddsNoDuplicates() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 10);

        JobExportImportSummary first = importer.importBatch(batch.jsonl(), batch.manifest());
        JobExportImportSummary second = importer.importBatch(batch.jsonl(), batch.manifest());

        assertEquals(10, first.inserted());
        assertEquals(0, second.inserted());
        assertEquals(10, second.unchanged(), "identical content must read as unchanged");
        assertEquals(10, countCatalog());
        assertEquals(10, countSourceRecords());
    }

    // ---------------------------------------------------------------------------
    // 3. the same posting, updated
    // ---------------------------------------------------------------------------

    @Test
    void aNewerVersionOfTheSamePostingUpdatesItInPlace() throws IOException {
        Batch first = batch("greenhouse", "vercel", posting -> {
        }, 1);
        importer.importBatch(first.jsonl(), first.manifest());
        Long catalogId = onlyCatalogId();

        Batch second = batch("greenhouse", "vercel", "20260902T000000Z-second0000", posting -> {
            posting.title = "Staff Java Developer";
            posting.description = "<p>Rewritten description with a raised scope.</p>";
        }, 1);
        JobExportImportSummary summary = importer.importBatch(second.jsonl(), second.manifest());

        assertEquals(1, summary.updated());
        assertEquals(1, countCatalog(), "an update must not create a second row");
        JobCatalogEntry entry = jobCatalogMapper.findById(catalogId);
        assertEquals("Staff Java Developer", entry.getTitle());
        assertTrue(entry.getDescriptionText().contains("raised scope"));
    }

    // ---------------------------------------------------------------------------
    // 4. same title, different requisition number
    // ---------------------------------------------------------------------------

    @Test
    void twoRequisitionsWithTheSameTitleAndPlaceStayApart() throws IOException {
        Batch batch = customBatch("greenhouse", "vercel", RUN, List.of(
                posting("REQ-4001", "Java Developer", "Vercel", "Hybrid - London",
                        "https://job-boards.greenhouse.io/vercel/jobs/4001"),
                posting("REQ-4002", "Java Developer", "Vercel", "Hybrid - London",
                        "https://job-boards.greenhouse.io/vercel/jobs/4002")));

        importer.importBatch(batch.jsonl(), batch.manifest());

        // Company, title and location all agree. Only the requisition number and the URL
        // differ, and that is enough: these are two openings.
        assertEquals(2, countCatalog());
        assertEquals(2, countSourceRecords());
    }

    // ---------------------------------------------------------------------------
    // 5. the same external id on two different boards
    // ---------------------------------------------------------------------------

    @Test
    void theSameExternalIdOnAnotherBoardIsADifferentPosting() throws IOException {
        Batch acme = customBatch("greenhouse", "acme", "20260901T000000Z-acme000000", List.of(
                posting("12345", "Backend Engineer", "Acme", "Berlin",
                        "https://job-boards.greenhouse.io/acme/jobs/12345")));
        Batch globex = customBatch("greenhouse", "globex", "20260901T000000Z-globex0000", List.of(
                posting("12345", "Frontend Engineer", "Globex", "Lisbon",
                        "https://job-boards.greenhouse.io/globex/jobs/12345")));

        importer.importBatch(acme.jsonl(), acme.manifest());
        importer.importBatch(globex.jsonl(), globex.manifest());

        // Identity is provider + board + external_id. Sharing the last part alone means
        // nothing: two boards number their postings independently.
        assertEquals(2, countCatalog());
        assertEquals(2, countSourceRecords());
        JobSource acmeSource = jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "acme");
        JobSource globexSource = jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "globex");
        assertNotEqualIds(acmeSource, globexSource);
        assertNotNull(jobSourceRecordMapper.findBySourceAndExternalId(acmeSource.getId(), "12345"));
        assertNotNull(jobSourceRecordMapper.findBySourceAndExternalId(globexSource.getId(), "12345"));
    }

    // ---------------------------------------------------------------------------
    // 6. a sample import expires nothing
    // ---------------------------------------------------------------------------

    @Test
    void aPostingMissingFromTheBatchIsLeftAloneRatherThanExpired() throws IOException {
        Batch seed = customBatch("greenhouse", "vercel", "20260831T000000Z-seed000000", List.of(
                posting("OLD-1", "Existing Role", "Vercel", "Remote - United States",
                        "https://job-boards.greenhouse.io/vercel/jobs/9001")));
        importer.importBatch(seed.jsonl(), seed.manifest());
        Long survivor = onlyCatalogId();
        assertEquals(CatalogJobStatus.ACTIVE.name(), jobCatalogMapper.findById(survivor).getStatus());

        // A later sample that does not mention OLD-1 at all.
        Batch sample = batch("greenhouse", "vercel", posting -> {
        }, 5);
        JobExportImportSummary summary = importer.importBatch(sample.jsonl(), sample.manifest());

        assertEquals(0, summary.expired());
        assertFalse(summary.completeSnapshot(), "a capped batch is not a snapshot of the board");
        JobCatalogEntry stillThere = jobCatalogMapper.findById(survivor);
        assertEquals(CatalogJobStatus.ACTIVE.name(), stillThere.getStatus(),
                "a sample import must never close what it did not carry");
        assertTrue(jobSourceRecordMapper.findBySourceAndExternalId(
                jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "vercel").getId(), "OLD-1").isActive());
        assertEquals(6, countCatalog());
    }

    @Test
    void anImportIsRecordedAsASampleNotAsASuccessfulCrawl() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 3);

        JobExportImportSummary summary = importer.importBatch(batch.jsonl(), batch.manifest());

        JobIngestRun run = jdbcTemplate.queryForObject(
                "SELECT id, source_id, status, fetched_count, inserted_count, expired_count "
                        + "FROM job_ingest_runs WHERE id = ?",
                (rs, n) -> {
                    JobIngestRun r = new JobIngestRun();
                    r.setId(rs.getLong("id"));
                    r.setStatus(rs.getString("status"));
                    r.setFetchedCount(rs.getInt("fetched_count"));
                    r.setExpiredCount(rs.getInt("expired_count"));
                    return r;
                }, summary.ingestRunId());
        assertNotNull(run);
        // SUCCESS is what licenses an expiry sweep. An import must not claim it.
        assertEquals(IngestRunStatus.SAMPLE_IMPORT.name(), run.getStatus());
        assertEquals(0, run.getExpiredCount());
    }

    // ---------------------------------------------------------------------------
    // 7. bad batches are refused before anything is written
    // ---------------------------------------------------------------------------

    @Test
    void aFailedManifestIsRefusedAndTheStaleJsonlBesideItIsNotImported() throws IOException {
        // Exactly the shape the exporter leaves behind when a crawl is blocked: a
        // manifest saying so, and a JSONL from an earlier, successful run still on disk.
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 5);
        Files.writeString(batch.manifest(), """
                {
                  "run_id": "%s",
                  "mode": "live",
                  "provider": "greenhouse",
                  "board": "vercel",
                  "exported_count": 5,
                  "truncated": false,
                  "complete_snapshot": false,
                  "status": "failed",
                  "error": "BLOCKED_BOT_CHALLENGE"
                }
                """.formatted(RUN), StandardCharsets.UTF_8);

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));

        assertTrue(ex.getMessage().contains("status=failed"));
        assertNothingWasWritten();
    }

    @Test
    void aTruncatedFileIsRefusedBecauseItDisagreesWithItsManifest() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 10);
        // Simulate a copy that stopped part-way: the manifest still claims 10.
        List<String> lines = Files.readAllLines(batch.jsonl(), StandardCharsets.UTF_8);
        Files.write(batch.jsonl(), lines.subList(0, 6), StandardCharsets.UTF_8);

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));

        assertTrue(ex.getMessage().contains("manifest declares 10"));
        assertNothingWasWritten();
    }

    @Test
    void aRecordFromAnotherBatchIsRefused() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 3);
        List<String> lines = new ArrayList<>(Files.readAllLines(batch.jsonl(), StandardCharsets.UTF_8));
        lines.set(1, lines.get(1).replace(RUN, "20260101T000000Z-someotherrun"));
        Files.write(batch.jsonl(), lines, StandardCharsets.UTF_8);

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));

        assertTrue(ex.getMessage().contains("mixed from more than one batch"));
        assertNothingWasWritten();
    }

    @Test
    void aRecordForAnotherBoardIsRefused() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 3);
        List<String> lines = new ArrayList<>(Files.readAllLines(batch.jsonl(), StandardCharsets.UTF_8));
        lines.set(2, lines.get(2).replace("\"board\":\"vercel\"", "\"board\":\"someone-else\""));
        Files.write(batch.jsonl(), lines, StandardCharsets.UTF_8);

        assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));
        assertNothingWasWritten();
    }

    @Test
    void aCorruptLineIsRefused() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 3);
        List<String> lines = new ArrayList<>(Files.readAllLines(batch.jsonl(), StandardCharsets.UTF_8));
        lines.set(1, "{\"run_id\": \"broken\", ");
        Files.write(batch.jsonl(), lines, StandardCharsets.UTF_8);

        assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));
        assertNothingWasWritten();
    }

    @Test
    void aRecordMissingItsIdentityIsRefused() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 3);
        List<String> lines = new ArrayList<>(Files.readAllLines(batch.jsonl(), StandardCharsets.UTF_8));
        lines.set(0, lines.get(0).replaceAll("\"external_id\":\"[^\"]*\"", "\"external_id\":\"\""));
        Files.write(batch.jsonl(), lines, StandardCharsets.UTF_8);

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));
        assertTrue(ex.getMessage().contains("external_id"));
        assertNothingWasWritten();
    }

    @Test
    void twoPostingsSharingOneApplyUrlAreRefusedRatherThanSilentlyMerged() throws IOException {
        // The cross-source merge key is a heuristic: it rules out careers landing pages,
        // but it cannot prove a board gives every posting its own address. Two postings in
        // one batch sharing a URL is direct evidence this board does not — and importing
        // both would collapse two requisitions into one catalog row.
        Batch batch = customBatch("greenhouse", "vercel", RUN, List.of(
                posting("REQ-1", "Backend Engineer", "Vercel", "London",
                        "https://acme.example.com/careers/engineering"),
                posting("REQ-2", "Frontend Engineer", "Vercel", "London",
                        "https://acme.example.com/careers/engineering")));

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));

        assertTrue(ex.getMessage().contains("reuses the apply URL"));
        assertNothingWasWritten();
    }

    @Test
    void aLaterBatchReusingAnEarlierPostingsApplyUrlIsRefused() throws IOException {
        // The in-batch collision check cannot see this: the two requisitions arrive in
        // separate imports. Left alone, the second one is new to the source, so ingestion
        // resolves it by identity URL, finds the first one's catalog row and merges them —
        // two requisitions silently become one posting.
        Batch first = customBatch("greenhouse", "vercel", "20260901T000000Z-first00000", List.of(
                posting("REQ-A", "Backend Engineer", "Vercel", "London",
                        "https://acme.example.com/careers/engineering")));
        importer.importBatch(first.jsonl(), first.manifest());
        assertEquals(1, countCatalog());

        Batch second = customBatch("greenhouse", "vercel", "20260901T000000Z-second0000", List.of(
                posting("REQ-B", "Frontend Engineer", "Vercel", "London",
                        "https://acme.example.com/careers/engineering")));

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(second.jsonl(), second.manifest()));
        assertTrue(ex.getMessage().contains("REQ-A"),
                "the rejection should name the requisition already holding the URL");

        // The earlier batch is untouched and the second one wrote nothing.
        assertEquals(1, countCatalog());
        assertEquals(1, countSourceRecords());
    }

    @Test
    void aSharedCareersLandingPageIsNotTreatedAsACollision() throws IOException {
        // The same URL twice, but this one is a landing page, so it is not an identity
        // URL at all and never becomes a merge key. Both postings import normally.
        Batch batch = customBatch("greenhouse", "vercel", RUN, List.of(
                posting("REQ-1", "Backend Engineer", "Vercel", "London",
                        "https://acme.example.com/careers"),
                posting("REQ-2", "Frontend Engineer", "Vercel", "London",
                        "https://acme.example.com/careers")));

        importer.importBatch(batch.jsonl(), batch.manifest());

        assertEquals(2, countCatalog());
        List<String> norms = jdbcTemplate.queryForList(
                "SELECT apply_url_norm FROM job_catalog", String.class);
        assertTrue(norms.stream().allMatch(java.util.Objects::isNull));
    }

    @Test
    void anExternalIdPaddedWithWhitespaceIsRefusedRatherThanTrimmed() throws IOException {
        // Trimming would be the quiet failure: "REQ-A " would be stored as "REQ-A" and
        // from then on be a different posting's identity, so this code would have caused
        // the merge. The value is never rewritten, so a padded one is refused instead.
        Batch batch = customBatch("greenhouse", "vercel", RUN, List.of(
                posting("REQ-A ", "Backend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1")));

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));

        assertTrue(ex.getMessage().contains("padded with whitespace"));
        assertNothingWasWritten();
    }

    @Test
    void anExternalIdWithInnerPunctuationOrSpacingIsKeptVerbatim() throws IOException {
        // Only SURROUNDING whitespace is refused. What a board puts inside its own id is
        // the board's business, and rewriting any of it would change the identity.
        String id = "REQ 2024/A-b_c.d~e";
        Batch batch = customBatch("greenhouse", "vercel", RUN, List.of(
                posting(id, "Backend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1")));

        importer.importBatch(batch.jsonl(), batch.manifest());

        Long sourceId = jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "vercel").getId();
        assertEquals(id, jobSourceRecordMapper.findBySourceAndExternalId(sourceId, id)
                .getExternalJobId());
    }

    // ---------------------------------------------------------------------------
    // the source allowlist, enforced here and not only in the pipeline script
    // ---------------------------------------------------------------------------

    @Test
    void aBatchForABoardNotOnTheAllowlistIsRefused() throws IOException {
        // The batch is perfectly well-formed. What makes it unacceptable is that nobody
        // wrote down why this deployment may collect from that board -- and the check
        // lives here, not only in the script, so a hand-run CLI cannot bypass it.
        Batch batch = customBatch("greenhouse", "unlisted", RUN, List.of(
                posting("REQ-1", "Backend Engineer", "Unlisted Corp", "London",
                        "https://job-boards.greenhouse.io/unlisted/jobs/1")));

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));

        assertTrue(ex.getMessage().contains("not in"));
        assertTrue(ex.getMessage().contains("greenhouse/unlisted"));
        assertNothingWasWritten();
    }

    @Test
    void aBatchForADisabledAllowlistEntryIsRefused() throws IOException {
        // Listed, with its reasoning kept, but switched off. Turning a board off has to
        // actually stop imports, or the flag is decoration.
        Batch batch = customBatch("greenhouse", "retired", RUN, List.of(
                posting("REQ-1", "Backend Engineer", "Retired Corp", "London",
                        "https://job-boards.greenhouse.io/retired/jobs/1")));

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));

        assertTrue(ex.getMessage().contains("disabled"));
        assertNothingWasWritten();
    }

    @Test
    void anAllowlistEntryWithNoStatedEvidenceIsRefused() throws IOException {
        // The point of the file is the reasoning, not the list of names. An entry someone
        // added without saying why is one nobody checked.
        Batch batch = customBatch("greenhouse", "no-evidence", RUN, List.of(
                posting("REQ-1", "Backend Engineer", "No Evidence Corp", "London",
                        "https://job-boards.greenhouse.io/no-evidence/jobs/1")));

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));

        assertTrue(ex.getMessage().contains("evidence"));
        assertNothingWasWritten();
    }

    @Test
    void aFileThatIsNotUtf8IsRefused() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 2);
        byte[] valid = Files.readAllBytes(batch.jsonl());
        byte[] corrupt = new byte[valid.length + 2];
        System.arraycopy(valid, 0, corrupt, 0, valid.length);
        // A lone continuation byte: not a legal UTF-8 sequence anywhere.
        corrupt[valid.length] = (byte) 0x80;
        corrupt[valid.length + 1] = (byte) 0x0A;
        Files.write(batch.jsonl(), corrupt);

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));
        assertTrue(ex.getMessage().contains("UTF-8"));
        assertNothingWasWritten();
    }

    @Test
    void aMissingManifestIsRefusedRatherThanGuessed() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 3);

        assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batchDir.resolve("absent.manifest.json")));
        assertNothingWasWritten();
    }

    // ---------------------------------------------------------------------------
    // 8. content survives the round trip
    // ---------------------------------------------------------------------------

    @Test
    void theFullDescriptionUnicodeLinkAndMultiCityLocationAllSurvive() throws IOException {
        String multiCity = "Hybrid - San Francisco, New York City, Austin";
        String unicodeJd = "<h2>Über uns</h2><p>We free people to ship what’s next — "
                + "上海 · Café · 🚀</p><p>" + "x".repeat(4000) + "</p>";
        Batch batch = customBatch("greenhouse", "vercel", RUN, List.of(
                posting("6136160004", "Account Executive, Commercial", "Vercel", multiCity,
                        "https://job-boards.greenhouse.io/vercel/jobs/6136160004",
                        unicodeJd)));

        importer.importBatch(batch.jsonl(), batch.manifest());

        JobCatalogEntry entry = jobCatalogMapper.findById(onlyCatalogId());
        assertEquals(multiCity, entry.getLocationText(), "the board's own location string is kept whole");
        assertEquals("https://job-boards.greenhouse.io/vercel/jobs/6136160004", entry.getApplyUrl());
        assertTrue(entry.getDescriptionHtml().contains("Über uns"));
        assertTrue(entry.getDescriptionText().contains("what’s next"));
        assertTrue(entry.getDescriptionText().contains("上海"));
        assertTrue(entry.getDescriptionText().contains("🚀"));
        assertTrue(entry.getDescriptionHtml().length() > 4000, "the description is not truncated");
    }

    @Test
    void aGreenhouseTimestampIsNotStoredAsAPublicationDate() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 1);

        importer.importBatch(batch.jsonl(), batch.manifest());

        JobCatalogEntry entry = jobCatalogMapper.findById(onlyCatalogId());
        // Greenhouse's board API publishes no posting date; upstream fills the field from
        // updated_at. An unknown date stays NULL rather than becoming a wrong one.
        assertNull(entry.getPostedAt());
        // The value itself is not lost — it is on the source record's stored payload.
        JobSourceRecord record = jobSourceRecordMapper.findBySourceAndExternalId(
                jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "vercel").getId(), "REQ-0");
        assertTrue(record.getRawPayload().contains("posted_at"));
    }

    @Test
    void anAbsentFacetIsLeftUnspecifiedRatherThanGuessed() throws IOException {
        Batch batch = batch("greenhouse", "vercel", posting -> {
        }, 1);

        importer.importBatch(batch.jsonl(), batch.manifest());

        JobCatalogEntry entry = jobCatalogMapper.findById(onlyCatalogId());
        // remote=false means the location text did not say "remote", not that the
        // employer stated the role is on-site.
        assertEquals(RemoteType.UNSPECIFIED.name(), entry.getRemoteType());
        assertEquals("UNSPECIFIED", entry.getEmploymentType());
        assertNull(entry.getCountryCode());
    }

    // ---------------------------------------------------------------------------
    // 9. the imported postings are findable through the existing search
    // ---------------------------------------------------------------------------

    @Test
    void importedPostingsAreFoundByTheExistingCatalogSearch() throws IOException {
        Batch batch = customBatch("greenhouse", "vercel", RUN, List.of(
                posting("REQ-1", "Senior Java Developer", "Vercel", "Hybrid - London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1"),
                posting("REQ-2", "Platform Engineer", "Vercel", "Remote - United States",
                        "https://job-boards.greenhouse.io/vercel/jobs/2")));
        importer.importBatch(batch.jsonl(), batch.manifest());

        JobCatalogSearchResponse byTitle = search("Platform Engineer");
        assertEquals(1, byTitle.results().size());
        assertEquals("Platform Engineer", byTitle.results().get(0).title());

        JobCatalogSearchResponse byCompany = search("Vercel");
        assertEquals(2, byCompany.results().size());
        for (JobCatalogSummary summary : byCompany.results()) {
            assertEquals("Vercel", summary.companyName());
            assertNotNull(summary.applyUrl());
        }
    }

    // ---------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------

    private JobCatalogSearchResponse search(String q) {
        return jobCatalogService.search(ALICE, new JobCatalogSearchRequest(
                q, null, null, null, null, null, null, null, null));
    }

    private void assertNothingWasWritten() {
        assertEquals(0, countCatalog(), "a refused batch must leave the catalog untouched");
        assertEquals(0, countSourceRecords());
        assertEquals(0, countIngestRuns(), "a refused batch must not even open a run row");
    }

    private static void assertNotEqualIds(JobSource a, JobSource b) {
        assertNotNull(a);
        assertNotNull(b);
        assertFalse(a.getId().equals(b.getId()), "two boards must not share one source row");
    }

    private int countCatalog() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_catalog", Integer.class);
    }

    private int countSourceRecords() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_source_records", Integer.class);
    }

    private int countIngestRuns() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_ingest_runs", Integer.class);
    }

    private Long onlyCatalogId() {
        return jdbcTemplate.queryForList("SELECT id FROM job_catalog ORDER BY id", Long.class).get(0);
    }

    // --- synthetic batch generation -------------------------------------------

    /** A generated batch: the JSONL and the manifest that describes it. */
    private record Batch(Path jsonl, Path manifest) {
    }

    /** A mutable posting, so a test can bend one field without restating them all. */
    private static final class Posting {
        String externalId;
        String title = "Java Developer";
        String company = "Vercel";
        String location = "Hybrid - London";
        String applyUrl;
        String description = "<h2>About the role</h2><p>Build and operate services.</p>";
        String postedAt = "2026-08-18T18:06:19Z";
    }

    private Batch batch(String provider, String board, java.util.function.Consumer<Posting> tweak, int count)
            throws IOException {
        return batch(provider, board, RUN, tweak, count);
    }

    private Batch batch(String provider, String board, String runId,
                        java.util.function.Consumer<Posting> tweak, int count) throws IOException {
        List<Posting> postings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Posting p = new Posting();
            p.externalId = "REQ-" + i;
            p.title = "Java Developer " + i;
            p.applyUrl = "https://job-boards.greenhouse.io/" + board + "/jobs/" + (7000000 + i);
            tweak.accept(p);
            postings.add(p);
        }
        return customBatch(provider, board, runId, postings);
    }

    private static Posting posting(String externalId, String title, String company,
                                   String location, String applyUrl) {
        return posting(externalId, title, company, location, applyUrl,
                "<h2>About the role</h2><p>Build and operate services.</p>");
    }

    private static Posting posting(String externalId, String title, String company,
                                   String location, String applyUrl, String description) {
        Posting p = new Posting();
        p.externalId = externalId;
        p.title = title;
        p.company = company;
        p.location = location;
        p.applyUrl = applyUrl;
        p.description = description;
        return p;
    }

    private Batch customBatch(String provider, String board, String runId, List<Posting> postings)
            throws IOException {
        StringBuilder jsonl = new StringBuilder();
        for (Posting p : postings) {
            jsonl.append("{\"run_id\":\"").append(runId)
                    .append("\",\"provider\":\"").append(provider)
                    .append("\",\"board\":\"").append(board)
                    .append("\",\"external_id\":\"").append(p.externalId)
                    .append("\",\"title\":").append(json(p.title))
                    .append(",\"company\":").append(json(p.company))
                    .append(",\"location\":").append(json(p.location))
                    .append(",\"apply_url\":").append(json(p.applyUrl))
                    .append(",\"description\":").append(json(p.description))
                    .append(",\"remote\":false")
                    .append(",\"posted_at\":\"").append(p.postedAt)
                    .append("\",\"fetched_at\":\"2026-09-01T16:17:00Z\"}\n");
        }
        Path jsonlPath = batchDir.resolve(board + "-" + runId + ".jsonl");
        Files.writeString(jsonlPath, jsonl.toString(), StandardCharsets.UTF_8);

        Path manifestPath = batchDir.resolve(board + "-" + runId + ".manifest.json");
        Files.writeString(manifestPath, """
                {
                  "run_id": "%s",
                  "mode": "live",
                  "provider": "%s",
                  "board": "%s",
                  "company": "Vercel",
                  "fetched_count": %d,
                  "exported_count": %d,
                  "max_export": 50,
                  "truncated": %s,
                  "complete_snapshot": false,
                  "status": "ok"
                }
                """.formatted(runId, provider, board, postings.size() + 39, postings.size(), "true"),
                StandardCharsets.UTF_8);

        return new Batch(jsonlPath, manifestPath);
    }

    /** Minimal JSON string escaping for the generated fixtures. */
    private static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
