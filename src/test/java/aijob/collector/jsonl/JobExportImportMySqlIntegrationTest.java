package aijob.collector.jsonl;

import aijob.AiJobApplication;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.entity.JobCatalogEntry;
import aijob.entity.JobSource;
import aijob.entity.JobSourceRecord;
import aijob.enums.CatalogJobStatus;
import aijob.enums.IngestRunStatus;
import aijob.mapper.JobCatalogMapper;
import aijob.mapper.JobSourceMapper;
import aijob.mapper.JobSourceRecordMapper;
import aijob.service.JobCatalogService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The import path against a real MySQL, on an explicitly supplied ISOLATED test database.
 *
 * <p>H2 in MySQL mode is not MySQL. It differs on exactly the things this test is for:
 * collation and case sensitivity on the unique keys, {@code utf8mb4} storage of emoji,
 * column widths, and whether the production DDL — prepared statements, the FULLTEXT
 * index, the structure gate — executes at all.
 *
 * <p>The connection is supplied through the environment, never on a command line: a
 * {@code -Dpassword=...} argument is readable by every other process on the machine and
 * is kept in the shell's history. There is deliberately no fallback to the application's
 * own datasource configuration — an unset variable skips the class rather than quietly
 * connecting to whatever this deployment normally uses.
 *
 * <pre>
 * .\infra\mysql-test\run-mysql-tests.ps1
 * </pre>
 *
 * <p>Before it touches anything it checks, in order: the server really is 8.4, the
 * connected schema is exactly the expected name, the account's grants reach that schema
 * and nothing else, and the schema holds no table this project does not own. A database
 * whose name merely contains "test" is not sufficient evidence to start dropping tables
 * in it.
 *
 * <p>The schema is built from the files a deployment actually applies —
 * {@code infra/aws/mysql-init/01-schema.sql} for an empty database, then
 * {@code schema/job_catalog_phase1.sql}, whose own structure gate has to pass — rather
 * than from a hand-written approximation that could be wrong in the same way twice.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                // Read straight from the environment. No default is given for the URL, so
                // a context can only ever be built when the variable is genuinely set --
                // and @EnabledIfEnvironmentVariable stops the class before that anyway.
                "spring.datasource.url=${AIJOB_MYSQL_TEST_URL}",
                "spring.datasource.username=${AIJOB_MYSQL_TEST_USERNAME}",
                "spring.datasource.password=${AIJOB_MYSQL_TEST_PASSWORD}",
                // src/test/resources/application.yml shadows the main config and pins the
                // H2 driver for every other test. Without this override Hikari is handed
                // a MySQL URL and an H2 driver, and fails with "Driver org.h2.Driver
                // claims to not accept jdbcUrl" before it ever reaches the server.
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads",
                "aijob.collector.allowlist-file=src/test/resources/config/test-sources-allowlist.json",
                "aijob.job-catalog.provider=local_catalog"
        }
)
@EnabledIfEnvironmentVariable(named = "AIJOB_MYSQL_TEST_URL", matches = ".+",
        disabledReason = "set AIJOB_MYSQL_TEST_URL/USERNAME/PASSWORD (see infra/mysql-test) "
                + "to run against an isolated MySQL 8.4")
class JobExportImportMySqlIntegrationTest {

    private static final String URL_VARIABLE = "AIJOB_MYSQL_TEST_URL";
    private static final String ALICE = "alice@example.com";
    private static final String RUN = "20260901T161700Z-mysql000000";

    /** The only server version this is meant to answer questions about. */
    private static final String REQUIRED_SERVER_VERSION_PREFIX = "8.4";

    /** The only schema this may write to, matched exactly rather than by substring. */
    private static final String REQUIRED_SCHEMA = "aijob_import_test";

    /** The migration chain, in the order release.sh applies it. */
    private static final String PHASE1_MIGRATION = "schema/job_catalog_phase1.sql";
    private static final String EXTERNAL_ID_MIGRATION =
            "schema/job_catalog_external_id_exact_identity.sql";
    private static final String NO_PAD_MIGRATION =
            "schema/job_catalog_external_id_no_pad.sql";
    /** Unrelated to the job catalog tables above, but chained right after them by
     *  release.sh -- kept in this same ordered list so this class keeps building the
     *  schema exactly the way a real release does. */
    private static final String APPLIED_TRACKING_MIGRATION =
            "schema/jobs_applied_tracking.sql";
    private static final String STRUCTURE_VERIFIER = "schema/job_catalog_phase1_verify.sql";

    /** What the identity column must end up as: binary comparison AND no space padding. */
    private static final String IDENTITY_COLLATION = "utf8mb4_0900_bin";

    /**
     * Every table this project owns. A schema containing anything else is somebody's
     * database, whatever it is called, and this test will not create or truncate in it.
     */
    private static final Set<String> OWNED_TABLES = Set.of(
            "users", "resumes", "jobs", "tailor_proposals", "resume_evidence_items",
            "job_requirement_profiles", "schema_migrations", "job_sources", "job_catalog",
            "job_source_records", "job_ingest_runs", "user_saved_catalog_jobs", "site_feedback",
            "resume_builder_drafts", "resume_builder_generation_requests", "job_search_history");

    @Autowired
    private DataSource dataSource;

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
    void setUp() throws SQLException {
        String url = System.getenv(URL_VARIABLE);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "set " + URL_VARIABLE + " to an ISOLATED MySQL 8.4 test database to run this");
        assertTrue(url.startsWith("jdbc:mysql:"), "not a MySQL connection string");

        verifyServerIsSafeToUse();
        applyProductionSchema();
        truncateAll();
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                ALICE, "not-a-real-hash", "Alice");
    }

    /**
     * Four checks, all against the live connection, all before a single DDL statement.
     *
     * <p>A name is not evidence. The database this connects to must actually be 8.4, must
     * actually be called {@code aijob_import_test}, the account must actually be unable to
     * reach any other schema, and the schema must actually contain nothing but this
     * project's own tables. Any of those failing aborts before {@link #truncateAll()} —
     * which is the method that would otherwise erase somebody's data.
     */
    private void verifyServerIsSafeToUse() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertNotNull(version);
        assertTrue(version.startsWith(REQUIRED_SERVER_VERSION_PREFIX),
                "this suite exists to answer MySQL " + REQUIRED_SERVER_VERSION_PREFIX
                        + " questions, and the server reports " + version
                        + "; results from another version would not mean what they claim");

        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertEquals(REQUIRED_SCHEMA, schema,
                "refusing to run: connected to schema '" + schema + "', expected exactly '"
                        + REQUIRED_SCHEMA + "'");

        // SHOW GRANTS is the account's real reach, not what someone believes it to be.
        List<String> grants = jdbcTemplate.queryForList(
                "SHOW GRANTS FOR CURRENT_USER()", String.class);
        boolean onTestSchema = grants.stream().anyMatch(g -> g.contains("`" + REQUIRED_SCHEMA + "`.*"));
        assertTrue(onTestSchema,
                "the account has no grant on `" + REQUIRED_SCHEMA + "`.*; its grants are " + grants);
        // A global grant beyond USAGE means this account can read or write other schemas,
        // and an account that can do that must not be handed to a test that truncates.
        List<String> global = grants.stream()
                .filter(g -> g.contains(" ON *.*"))
                .filter(g -> !g.startsWith("GRANT USAGE ON *.*"))
                .toList();
        assertTrue(global.isEmpty(),
                "refusing to run: the account holds server-wide privileges " + global
                        + "; it must be scoped to " + REQUIRED_SCHEMA + " alone");

        // Finally, the contents. A schema holding a table this project does not own is
        // somebody's database whatever it is named.
        List<String> unexpected = jdbcTemplate.queryForList(
                        "SELECT table_name FROM information_schema.TABLES WHERE table_schema = DATABASE()",
                        String.class)
                .stream()
                .filter(t -> !OWNED_TABLES.contains(t.toLowerCase(java.util.Locale.ROOT)))
                .toList();
        assertTrue(unexpected.isEmpty(),
                "refusing to run: '" + schema + "' holds tables this project does not own "
                        + unexpected + "; it is not a scratch database");
    }

    /**
     * Builds the schema from the deployment's own files. Applying the additive migration
     * on top of the empty-database bootstrap is also what proves its structure gate passes
     * on a database that already has the tables — the re-run case.
     */
    private void applyProductionSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("infra/aws/mysql-init/01-schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(PHASE1_MIGRATION));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(EXTERNAL_ID_MIGRATION));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(NO_PAD_MIGRATION));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(APPLIED_TRACKING_MIGRATION));
        }
    }

    /**
     * Runs the identity fix as a release does: both files, in order. It is two migrations
     * because the first one's collation turned out to be only half a fix, and a caller
     * that ran just the first would be testing an intermediate state no deployment is
     * meant to stop at.
     */
    private void applyIdentityMigrations() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(EXTERNAL_ID_MIGRATION));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(NO_PAD_MIGRATION));
        }
    }

    /** Runs one SQL file, letting its own abort gate surface as an exception. */
    private void applyScript(String path) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
        }
    }

    private String externalIdCollation() {
        return jdbcTemplate.queryForObject("""
                SELECT collation_name FROM information_schema.COLUMNS
                 WHERE table_schema = DATABASE()
                   AND table_name = 'job_source_records'
                   AND column_name = 'external_job_id'
                """, String.class);
    }

    private void truncateAll() {
        Set<String> tables = new LinkedHashSet<>(List.of("user_saved_catalog_jobs", "job_ingest_runs",
                "job_source_records", "job_catalog", "job_sources", "jobs", "users"));
        addReferencingTables(tables);
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : tables) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    /** With FK checks off, truncating users/jobs alone leaves rows in tables that reference
     * them (resumes, drafts, ...) pointing at ids AUTO_INCREMENT hands out again, so a later
     * suite's new user silently inherits them. Truncate every referencing table too. */
    private void addReferencingTables(Set<String> tables) {
        boolean added = true;
        while (added) {
            String placeholders = String.join(",", java.util.Collections.nCopies(tables.size(), "?"));
            List<String> children = jdbcTemplate.queryForList(
                    "SELECT DISTINCT table_name FROM information_schema.KEY_COLUMN_USAGE"
                            + " WHERE table_schema = DATABASE() AND referenced_table_name IN (" + placeholders + ")",
                    String.class, tables.toArray());
            added = tables.addAll(children);
        }
    }

    // ---------------------------------------------------------------------------

    @Test
    void theProductionDdlAcceptsEverythingTheImporterWrites() throws IOException {
        Batch batch = batch(50);

        JobExportImportSummary summary = importer.importBatch(batch.jsonl(), batch.manifest());

        assertEquals(50, summary.inserted());
        assertEquals(0, summary.failed());
        assertEquals(50, count("job_catalog"));

        // SAMPLE_IMPORT has to fit job_ingest_runs.status VARCHAR(16) and survive a
        // round trip; a silent truncation here would turn it into something the expiry
        // logic might read differently.
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM job_ingest_runs WHERE id = ?", String.class, summary.ingestRunId());
        assertEquals(IngestRunStatus.SAMPLE_IMPORT.name(), status);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT expired_count FROM job_ingest_runs WHERE id = ?", Integer.class,
                summary.ingestRunId()));
    }

    @Test
    void identityIsProviderPlusBoardPlusExternalId() throws IOException {
        Batch acme = batch("acme", "20260901T000000Z-acme000000", List.of(
                posting("12345", "Backend Engineer", "Acme", "Berlin",
                        "https://job-boards.greenhouse.io/acme/jobs/12345")));
        Batch globex = batch("globex", "20260901T000000Z-globex0000", List.of(
                posting("12345", "Frontend Engineer", "Globex", "Lisbon",
                        "https://job-boards.greenhouse.io/globex/jobs/12345")));

        importer.importBatch(acme.jsonl(), acme.manifest());
        importer.importBatch(globex.jsonl(), globex.manifest());

        // The unique key is (source_id, external_job_id), and the two boards have
        // different source ids, so a shared external id is not a collision.
        assertEquals(2, count("job_catalog"));
        assertEquals(2, count("job_source_records"));
    }

    /**
     * The one behaviour H2 cannot answer.
     *
     * <p>{@code job_source_records} is {@code utf8mb4_unicode_ci} — a CASE-INSENSITIVE
     * collation — and its unique key covers {@code external_job_id}. So on MySQL, and not
     * on H2, two postings whose ids differ only in case collide on that key. Greenhouse
     * numbers its postings and Lever uses lowercase UUIDs, so nothing in scope today hits
     * this; a provider with mixed-case ids would.
     *
     * <p>This test records what the database actually does rather than what the schema
     * was assumed to do.
     */
    @Test
    void externalIdsDifferingOnlyInCaseAreNotSilentlyMerged() throws IOException {
        Batch batch = batch("vercel", RUN, List.of(
                posting("REQ-abc", "Backend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1"),
                posting("REQ-ABC", "Frontend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/2")));

        JobExportImportSummary summary = importer.importBatch(batch.jsonl(), batch.manifest());

        String collation = jdbcTemplate.queryForObject(
                "SELECT table_collation FROM information_schema.TABLES "
                        + "WHERE table_schema = DATABASE() AND table_name = 'job_source_records'",
                String.class);
        assertNotNull(collation);

        // Two requisitions went in. Either both are stored, or the collation folded them —
        // and if it folded them the importer must have counted a failure rather than
        // quietly keeping one.
        int stored = count("job_source_records");
        assertEquals(2, stored,
                "external ids differing only in case were merged by collation " + collation
                        + "; identity must not depend on letter case");
        assertEquals(2, summary.inserted());
        assertEquals(0, summary.failed());
    }

    @Test
    void anExactRepeatOfTheSameIdIsStillOneRow() throws IOException {
        // The other half of the contract. Making identity case-sensitive must not make it
        // stop de-duplicating the id a board actually reuses.
        Batch batch = batch("vercel", RUN, List.of(
                posting("REQ-abc", "Backend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1")));

        importer.importBatch(batch.jsonl(), batch.manifest());
        JobExportImportSummary second = importer.importBatch(batch.jsonl(), batch.manifest());

        assertEquals(0, second.inserted());
        assertEquals(1, second.unchanged());
        assertEquals(1, count("job_source_records"));
    }

    @Test
    void updatingOneCaseVariantLeavesTheOtherAlone() throws IOException {
        Batch first = batch("vercel", RUN, List.of(
                posting("REQ-abc", "Backend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1"),
                posting("REQ-ABC", "Frontend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/2")));
        importer.importBatch(first.jsonl(), first.manifest());
        assertEquals(2, count("job_source_records"));

        // A later batch that revises only the lower-case one.
        Batch second = batch("vercel", "20260901T161700Z-mysql000002", List.of(
                posting("REQ-abc", "Staff Backend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1")));
        JobExportImportSummary summary = importer.importBatch(second.jsonl(), second.manifest());

        assertEquals(1, summary.updated());
        assertEquals(0, summary.inserted());
        assertEquals(2, count("job_source_records"));

        Long sourceId = jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "vercel").getId();
        JobSourceRecord lower = jobSourceRecordMapper.findBySourceAndExternalId(sourceId, "REQ-abc");
        JobSourceRecord upper = jobSourceRecordMapper.findBySourceAndExternalId(sourceId, "REQ-ABC");
        assertNotNull(lower);
        assertNotNull(upper);
        assertNotEquals(lower.getId(), upper.getId());
        // The lookup itself must be exact too, not only the constraint: a case-insensitive
        // WHERE would have returned whichever row MySQL reached first for both calls.
        assertEquals("REQ-abc", lower.getExternalJobId());
        assertEquals("REQ-ABC", upper.getExternalJobId());
        assertEquals("Staff Backend Engineer",
                jobCatalogMapper.findById(lower.getCatalogJobId()).getTitle());
        assertEquals("Frontend Engineer",
                jobCatalogMapper.findById(upper.getCatalogJobId()).getTitle(),
                "updating one requisition must not overwrite its case variant");
    }

    @Test
    void theSameCaseVariantOnAnotherBoardIsStillSeparate() throws IOException {
        Batch acme = batch("acme", "20260901T161700Z-mysql00acme", List.of(
                posting("REQ-abc", "Backend Engineer", "Acme", "Berlin",
                        "https://job-boards.greenhouse.io/acme/jobs/1")));
        Batch globex = batch("globex", "20260901T161700Z-mysql00glbx", List.of(
                posting("REQ-abc", "Frontend Engineer", "Globex", "Lisbon",
                        "https://job-boards.greenhouse.io/globex/jobs/1")));

        importer.importBatch(acme.jsonl(), acme.manifest());
        importer.importBatch(globex.jsonl(), globex.manifest());

        // Identity is provider + board + external id. The board half still separates them.
        assertEquals(2, count("job_source_records"));
        assertEquals(2, count("job_catalog"));
    }

    // ---------------------------------------------------------------------------
    // the migration itself
    // ---------------------------------------------------------------------------

    @Test
    void aFreshDatabaseGetsTheExactCollationFromItsOwnDdl() {
        // setUp() just built this schema from the bootstrap files. A new install must be
        // correct on its own, without waiting for a migration to repair it.
        assertEquals(IDENTITY_COLLATION, externalIdCollation());
    }

    @Test
    void anExistingTableIsUpgradedByTheMigrationNotOnlyByCreateTable() throws SQLException {
        // CREATE TABLE IF NOT EXISTS does nothing to a table that already exists, so the
        // only thing that can fix a deployed database is the ALTER. Put the column back
        // the way it was before this fix and check the migration actually moves it.
        jdbcTemplate.execute("""
                ALTER TABLE job_source_records
                    MODIFY external_job_id VARCHAR(190)
                    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
                """);
        jdbcTemplate.update("DELETE FROM schema_migrations WHERE version = ?",
                "2026-09-01-job-source-records-external-id-binary");
        assertEquals("utf8mb4_unicode_ci", externalIdCollation(), "precondition: the old state");

        applyIdentityMigrations();

        assertEquals(IDENTITY_COLLATION, externalIdCollation());
        assertEquals(1, count("schema_migrations WHERE version = "
                + "'2026-09-01-job-source-records-external-id-binary'"));
    }

    @Test
    void theMigrationPreservesLengthNullabilityAndTheUniqueKey() {
        Map<String, Object> column = jdbcTemplate.queryForMap("""
                SELECT character_maximum_length AS len, is_nullable AS nullable
                  FROM information_schema.COLUMNS
                 WHERE table_schema = DATABASE()
                   AND table_name = 'job_source_records'
                   AND column_name = 'external_job_id'
                """);
        assertEquals(190L, ((Number) column.get("len")).longValue());
        assertEquals("NO", column.get("nullable"));

        List<Map<String, Object>> key = jdbcTemplate.queryForList("""
                SELECT column_name, seq_in_index, non_unique
                  FROM information_schema.STATISTICS
                 WHERE table_schema = DATABASE()
                   AND table_name = 'job_source_records'
                   AND index_name = 'uk_job_source_records_source_external'
                 ORDER BY seq_in_index
                """);
        assertEquals(2, key.size(), "the unique key must survive the column rebuild");
        assertEquals("source_id", key.get(0).get("column_name"));
        assertEquals("external_job_id", key.get(1).get("column_name"));
        assertEquals(0, ((Number) key.get(0).get("non_unique")).intValue());
    }

    @Test
    void theMigrationLeavesHumanTextSearchableCaseInsensitively() throws IOException {
        // The fix must be one column wide. Title and company are human text, where a
        // search for "engineer" is supposed to find "Engineer".
        List<String> ciColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.COLUMNS
                 WHERE table_schema = DATABASE()
                   AND table_name = 'job_catalog'
                   AND column_name IN ('title', 'company_name', 'description_text')
                   AND collation_name = 'utf8mb4_unicode_ci'
                """, String.class);
        assertEquals(3, ciColumns.size(), "human text must stay case-insensitive, got " + ciColumns);

        assertEquals(1, count("information_schema.STATISTICS WHERE table_schema = DATABASE() "
                + "AND table_name = 'job_catalog' AND index_name = 'ft_job_catalog_text' "
                + "AND seq_in_index = 1"), "the full-text index must survive");

        // And behaviourally, not just structurally.
        Batch batch = batch("vercel", RUN, List.of(
                posting("REQ-1", "Backend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1")));
        importer.importBatch(batch.jsonl(), batch.manifest());
        JobCatalogSearchResponse lower = jobCatalogService.search(ALICE,
                new JobCatalogSearchRequest("backend engineer", null, null, null, null, null, null, null, 1, 20, null));
        assertEquals(1, lower.results().size(), "a lower-case query must still match a capitalised title");
    }

    // ---------------------------------------------------------------------------
    // trailing space: the half utf8mb4_bin did not fix
    // ---------------------------------------------------------------------------

    @Test
    void theIdentityCollationDoesNotPadTrailingSpaces() {
        // Read from the server rather than inferred from the collation's name. This is
        // the property the fix turns on: utf8mb4_bin is binary but PAD SPACE, so it still
        // compared "REQ-A" equal to "REQ-A " and folded them on the unique key.
        String pad = jdbcTemplate.queryForObject(
                "SELECT pad_attribute FROM information_schema.COLLATIONS WHERE collation_name = ?",
                String.class, externalIdCollation());
        assertEquals("NO PAD", pad,
                "an identity column on a PAD SPACE collation still merges values that differ"
                        + " only by trailing spaces");

        // And the comparison itself, in this column's own collation.
        String collation = externalIdCollation();
        Integer padded = jdbcTemplate.queryForObject(
                "SELECT CONVERT('REQ-A' USING utf8mb4) COLLATE " + collation
                        + " = CONVERT('REQ-A ' USING utf8mb4) COLLATE " + collation, Integer.class);
        Integer cased = jdbcTemplate.queryForObject(
                "SELECT CONVERT('REQ-A' USING utf8mb4) COLLATE " + collation
                        + " = CONVERT('req-a' USING utf8mb4) COLLATE " + collation, Integer.class);
        assertEquals(0, padded, "'REQ-A' and 'REQ-A ' must be different ids");
        assertEquals(0, cased, "'REQ-A' and 'req-a' must be different ids");
    }

    @Test
    void twoRowsDifferingOnlyByATrailingSpaceBothSurviveTheUniqueKey() {
        // Straight at the constraint, below the importer: on utf8mb4_bin the second
        // INSERT failed with a duplicate-key error.
        jdbcTemplate.update(
                "INSERT INTO job_sources (provider_type, tenant_key, display_name, enabled, "
                        + "crawl_allowed, collection_interval_minutes) "
                        + "VALUES ('GREENHOUSE', 'padtest', 'Pad Test', 0, 0, 120)");
        Long sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM job_sources WHERE tenant_key = 'padtest'", Long.class);

        for (String id : List.of("REQ-A", "REQ-A ")) {
            jdbcTemplate.update(
                    "INSERT INTO job_source_records (source_id, external_job_id, payload_hash, "
                            + "last_seen_at, active) VALUES (?, ?, 'hash', NOW(), 1)",
                    sourceId, id);
        }

        assertEquals(2, count("job_source_records"),
                "the unique key must treat a trailing space as part of the id");
        // And each is retrievable by its exact value, not whichever the server reached first.
        assertEquals("REQ-A", jdbcTemplate.queryForObject(
                "SELECT external_job_id FROM job_source_records "
                        + "WHERE source_id = ? AND external_job_id = 'REQ-A'", String.class, sourceId));
        assertEquals("REQ-A ", jdbcTemplate.queryForObject(
                "SELECT external_job_id FROM job_source_records "
                        + "WHERE source_id = ? AND external_job_id = 'REQ-A '", String.class, sourceId));
    }

    @Test
    void anImportedIdPaddedWithWhitespaceIsRefusedRatherThanTrimmed() throws IOException {
        // The Java half of the same question. Trimming would be the quiet failure: the
        // stored id would silently become another posting's identity, undoing at the
        // application layer exactly what NO PAD fixes at the database layer.
        Batch batch = batch("vercel", RUN, List.of(
                posting("REQ-A ", "Backend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1")));

        JobExportRejectedException ex = assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));

        assertTrue(ex.getMessage().contains("padded with whitespace"));
        assertTrue(ex.getMessage().contains("never trimmed"));
        assertEquals(0, count("job_source_records"), "a refused batch writes nothing");
        assertEquals(0, count("job_catalog"));
    }

    @Test
    void anUnpaddedIdIsStoredByteForByte() throws IOException {
        // The value the source gave, back out of the database unchanged.
        Batch batch = batch("vercel", RUN, List.of(
                posting("REQ-a_B.2~x", "Backend Engineer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1")));

        importer.importBatch(batch.jsonl(), batch.manifest());

        Long sourceId = jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "vercel").getId();
        assertEquals("REQ-a_B.2~x",
                jobSourceRecordMapper.findBySourceAndExternalId(sourceId, "REQ-a_B.2~x")
                        .getExternalJobId());
    }

    // ---------------------------------------------------------------------------
    // the migration chain
    // ---------------------------------------------------------------------------

    @Test
    void rerunningTheWholeChainDoesNotDowngradeTheCollation() throws SQLException {
        // The trap in a chain of migrations that each touch one column: the earlier file
        // still says "make this utf8mb4_bin", and a re-release runs it again. If its guard
        // tested equality it would drag the column back off NO PAD every single time, and
        // the newer migration would then quietly put it back -- with a window in between,
        // and no sign in the logs that anything was wrong.
        assertEquals(IDENTITY_COLLATION, externalIdCollation(), "precondition");

        for (int pass = 0; pass < 2; pass++) {
            applyScript(PHASE1_MIGRATION);
            assertEquals(IDENTITY_COLLATION, externalIdCollation(),
                    "phase 1 must not touch a column a later migration already strengthened");
            applyScript(EXTERNAL_ID_MIGRATION);
            assertEquals(IDENTITY_COLLATION, externalIdCollation(),
                    "the utf8mb4_bin migration must not downgrade utf8mb4_0900_bin on a re-run");
            applyScript(NO_PAD_MIGRATION);
            assertEquals(IDENTITY_COLLATION, externalIdCollation());
        }

        assertEquals(1, count("schema_migrations WHERE version = "
                + "'2026-09-01-job-source-records-external-id-no-pad'"));
        assertEquals(1, count("schema_migrations WHERE version = "
                + "'2026-09-01-job-source-records-external-id-binary'"));
    }

    @Test
    void aDatabaseStuckOnTheBinaryCollationIsCarriedForwardByTheNoPadMigration() throws SQLException {
        // A deployment that took the first fix and not the second: binary, still PAD SPACE.
        jdbcTemplate.execute("""
                ALTER TABLE job_source_records
                    MODIFY external_job_id VARCHAR(190)
                    CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
                """);
        jdbcTemplate.update("DELETE FROM schema_migrations WHERE version = ?",
                "2026-09-01-job-source-records-external-id-no-pad");
        assertEquals("utf8mb4_bin", externalIdCollation(), "precondition: the intermediate state");

        applyScript(NO_PAD_MIGRATION);

        assertEquals(IDENTITY_COLLATION, externalIdCollation());
        assertEquals(1, count("schema_migrations WHERE version = "
                + "'2026-09-01-job-source-records-external-id-no-pad'"));
    }

    @Test
    void theVerifierRejectsADatabaseLeftOnTheBinaryCollation() throws SQLException {
        // utf8mb4_bin passes a check that only asks "is it case-sensitive". The verifier
        // has to fail it, because that database still merges "REQ-A" with "REQ-A ".
        jdbcTemplate.execute("""
                ALTER TABLE job_source_records
                    MODIFY external_job_id VARCHAR(190)
                    CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
                """);

        assertThrows(Exception.class, () -> applyScript(STRUCTURE_VERIFIER),
                "a PAD SPACE identity column must not pass structure verification");

        applyScript(NO_PAD_MIGRATION);
        assertEquals(IDENTITY_COLLATION, externalIdCollation());
    }

    @Test
    void theStructureVerifierPassesOnAFullyMigratedDatabase() {
        // The file release.sh runs as its own gate before starting anything.
        assertDoesNotThrow(() -> applyScript(STRUCTURE_VERIFIER));
    }

    @Test
    void theStructureVerifierCatchesADatabaseThatSkippedTheMigration() throws SQLException {
        // The realistic failure: a release applied phase 1 and not the ALTER. Every table,
        // column, index and foreign key is present, so a structure check that only counted
        // those would call it healthy while it still merged case variants.
        jdbcTemplate.execute("""
                ALTER TABLE job_source_records
                    MODIFY external_job_id VARCHAR(190)
                    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
                """);

        assertThrows(Exception.class, () -> applyScript(STRUCTURE_VERIFIER),
                "the verifier must fail a database whose external ids still fold case");

        applyIdentityMigrations();
        assertEquals(IDENTITY_COLLATION, externalIdCollation());
    }

    @Test
    void theMigrationIsRepeatable() throws SQLException {
        String before = externalIdCollation();
        applyIdentityMigrations();
        applyIdentityMigrations();
        assertEquals(before, externalIdCollation());
        assertEquals(1, count("schema_migrations WHERE version = "
                + "'2026-09-01-job-source-records-external-id-binary'"),
                "the completion marker must not be duplicated");
    }

    @Test
    void fullDescriptionsUnicodeAndEmojiSurviveUtf8mb4() throws IOException {
        String jd = "<h2>Über uns</h2><p>ship what’s next — 上海 · Café · 🚀 · 𝕏</p><p>"
                + "x".repeat(20000) + "</p>";
        Batch batch = batch("vercel", RUN, List.of(
                posting("REQ-1", "Staff Engineer", "Vercel",
                        "Hybrid - San Francisco, New York City, Austin",
                        "https://job-boards.greenhouse.io/vercel/jobs/1", jd)));

        importer.importBatch(batch.jsonl(), batch.manifest());

        JobCatalogEntry entry = jobCatalogMapper.findById(onlyCatalogId());
        assertTrue(entry.getDescriptionHtml().contains("Über"));
        assertTrue(entry.getDescriptionText().contains("上海"));
        assertTrue(entry.getDescriptionText().contains("🚀"), "4-byte emoji must survive utf8mb4");
        assertTrue(entry.getDescriptionText().contains("𝕏"), "astral-plane characters must survive");
        assertTrue(entry.getDescriptionHtml().length() > 20000, "LONGTEXT must not truncate the body");
        assertEquals("Hybrid - San Francisco, New York City, Austin", entry.getLocationText());

        JobSourceRecord record = jobSourceRecordMapper.findBySourceAndExternalId(
                jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "vercel").getId(), "REQ-1");
        assertNotNull(record.getRawPayload());
        assertTrue(record.getRawPayload().contains("🚀"), "raw_payload keeps the record verbatim");
    }

    @Test
    void greenhousePostedAtStaysNull() throws IOException {
        Batch batch = batch(1);
        importer.importBatch(batch.jsonl(), batch.manifest());
        assertNull(jobCatalogMapper.findById(onlyCatalogId()).getPostedAt());
    }

    @Test
    void firstImportReimportAndUpdate() throws IOException {
        Batch first = batch(50);
        assertEquals(50, importer.importBatch(first.jsonl(), first.manifest()).inserted());

        JobExportImportSummary again = importer.importBatch(first.jsonl(), first.manifest());
        assertEquals(0, again.inserted());
        assertEquals(50, again.unchanged());
        assertEquals(50, count("job_catalog"));

        Batch changed = batch("vercel", "20260902T000000Z-update0000", List.of(
                postingWithId(0, "Staff Java Developer", "<p>Raised scope.</p>")));
        JobExportImportSummary updated = importer.importBatch(changed.jsonl(), changed.manifest());
        assertEquals(1, updated.updated());
        assertEquals(50, count("job_catalog"), "an update must not add a row");
    }

    @Test
    void sameTitleDifferentRequisitionsStayApart() throws IOException {
        Batch batch = batch("vercel", RUN, List.of(
                posting("REQ-4001", "Java Developer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/4001"),
                posting("REQ-4002", "Java Developer", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/4002")));

        importer.importBatch(batch.jsonl(), batch.manifest());

        assertEquals(2, count("job_catalog"));
    }

    @Test
    void anInvalidBatchWritesNothing() throws IOException {
        Batch batch = batch(10);
        List<String> lines = Files.readAllLines(batch.jsonl(), StandardCharsets.UTF_8);
        Files.write(batch.jsonl(), lines.subList(0, 4), StandardCharsets.UTF_8);

        assertThrows(JobExportRejectedException.class,
                () -> importer.importBatch(batch.jsonl(), batch.manifest()));

        assertEquals(0, count("job_catalog"));
        assertEquals(0, count("job_source_records"));
        assertEquals(0, count("job_ingest_runs"));
    }

    /**
     * A posting whose source-record write fails must take its catalog row with it. The
     * over-length external id is rejected by the column, not by application code, so this
     * exercises the real transaction boundary against the real engine.
     */
    @Test
    void aFailedRecordWriteRollsBackItsCatalogRow() throws IOException {
        Batch batch = batch("vercel", RUN, List.of(
                posting("R".repeat(300), "Broken", "Vercel", "London",
                        "https://job-boards.greenhouse.io/vercel/jobs/1")));

        JobExportImportSummary summary = importer.importBatch(batch.jsonl(), batch.manifest());

        assertEquals(1, summary.failed());
        assertEquals(0, count("job_catalog"), "a rolled-back posting leaves no orphan row");
        assertEquals(0, count("job_source_records"));
    }

    @Test
    void aSampleImportExpiresNothingAndLeavesAnExistingSourceUntouched() throws IOException {
        // A board that a real crawl already synced: enabled, with crawl state on it.
        jdbcTemplate.update("""
                INSERT INTO job_sources (provider_type, display_name, tenant_key, enabled,
                                         crawl_allowed, collection_interval_minutes,
                                         last_success_at, next_run_at, consecutive_failures)
                VALUES ('GREENHOUSE', 'Vercel', 'vercel', 1, 1, 120,
                        '2026-08-30 10:00:00', '2026-09-02 10:00:00', 3)
                """);
        Batch seed = batch("vercel", "20260830T000000Z-seed000000", List.of(
                posting("OLD-1", "Existing Role", "Vercel", "Remote",
                        "https://job-boards.greenhouse.io/vercel/jobs/9001")));
        importer.importBatch(seed.jsonl(), seed.manifest());
        Long survivor = onlyCatalogId();

        Batch sample = batch(5);
        JobExportImportSummary summary = importer.importBatch(sample.jsonl(), sample.manifest());

        assertEquals(0, summary.expired());
        assertFalse(summary.completeSnapshot());
        assertEquals(CatalogJobStatus.ACTIVE.name(), jobCatalogMapper.findById(survivor).getStatus());

        // The source's own crawl state is exactly as the real crawl left it.
        JobSource source = jobSourceMapper.findByProviderAndTenant("GREENHOUSE", "vercel");
        assertTrue(source.isEnabled(), "a sample import must not change a board's opt-in");
        assertTrue(source.isCrawlAllowed());
        assertEquals(3, source.getConsecutiveFailures(), "failure counters are not reset by an import");
        assertEquals("2026-08-30T10:00", source.getLastSuccessAt().toString(),
                "last_success_at belongs to the crawl, not to an import");
        assertEquals("2026-09-02T10:00", source.getNextRunAt().toString(),
                "an import must not reschedule the crawl");
    }

    @Test
    void importedPostingsAreFoundByTheExistingSearch() throws IOException {
        Batch batch = batch(5);
        importer.importBatch(batch.jsonl(), batch.manifest());

        JobCatalogSearchResponse found = jobCatalogService.search(ALICE, new JobCatalogSearchRequest(
                "Vercel", null, null, null, null, null, null, null, 1, 50, null));
        assertTrue(found.totalCount() > 0, "MySQL FULLTEXT search must find the imported postings");
    }

    // ---------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private Long onlyCatalogId() {
        return jdbcTemplate.queryForList("SELECT id FROM job_catalog ORDER BY id", Long.class).get(0);
    }

    private record Batch(Path jsonl, Path manifest) {
    }

    private record Posting(String externalId, String title, String company, String location,
                           String applyUrl, String description) {
    }

    private static Posting posting(String externalId, String title, String company,
                                   String location, String applyUrl) {
        return posting(externalId, title, company, location, applyUrl,
                "<h2>About the role</h2><p>Build and operate services.</p>");
    }

    private static Posting posting(String externalId, String title, String company,
                                   String location, String applyUrl, String description) {
        return new Posting(externalId, title, company, location, applyUrl, description);
    }

    private static Posting postingWithId(int index, String title, String description) {
        return new Posting("REQ-" + index, title, "Vercel", "Hybrid - London",
                "https://job-boards.greenhouse.io/vercel/jobs/" + (7000000 + index), description);
    }

    private Batch batch(int count) throws IOException {
        List<Posting> postings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            postings.add(postingWithId(i, "Java Developer " + i,
                    "<h2>About the role</h2><p>Build and operate services.</p>"));
        }
        return batch("vercel", RUN, postings);
    }

    private Batch batch(String board, String runId, List<Posting> postings) throws IOException {
        StringBuilder jsonl = new StringBuilder();
        for (Posting p : postings) {
            jsonl.append("{\"run_id\":\"").append(runId)
                    .append("\",\"provider\":\"greenhouse\",\"board\":\"").append(board)
                    .append("\",\"external_id\":").append(json(p.externalId()))
                    .append(",\"title\":").append(json(p.title()))
                    .append(",\"company\":").append(json(p.company()))
                    .append(",\"location\":").append(json(p.location()))
                    .append(",\"apply_url\":").append(json(p.applyUrl()))
                    .append(",\"description\":").append(json(p.description()))
                    .append(",\"remote\":false,\"posted_at\":\"2026-08-18T18:06:19Z\"")
                    .append(",\"fetched_at\":\"2026-09-01T16:17:00Z\"}\n");
        }
        Path jsonlPath = batchDir.resolve(board + "-" + runId + ".jsonl");
        Files.writeString(jsonlPath, jsonl.toString(), StandardCharsets.UTF_8);

        Path manifestPath = batchDir.resolve(board + "-" + runId + ".manifest.json");
        Files.writeString(manifestPath, """
                {
                  "run_id": "%s",
                  "mode": "live",
                  "provider": "greenhouse",
                  "board": "%s",
                  "company": "Vercel",
                  "fetched_count": %d,
                  "exported_count": %d,
                  "truncated": true,
                  "complete_snapshot": false,
                  "status": "ok"
                }
                """.formatted(runId, board, postings.size() + 39, postings.size()),
                StandardCharsets.UTF_8);
        return new Batch(jsonlPath, manifestPath);
    }

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
