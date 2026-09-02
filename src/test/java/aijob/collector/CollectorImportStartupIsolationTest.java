package aijob.collector;

import aijob.AiJobApplication;
import aijob.mapper.JobCatalogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Starts the real application the way an import invocation does, and checks what that
 * process is and is not allowed to do.
 *
 * <p>The import arguments are passed to {@code SpringApplication} itself, so the runners
 * execute during context startup exactly as they would in the container — this is a
 * startup test, not a call to a method that happens to be named like one.
 *
 * <p>{@code @Order} alone would not establish any of this. Ordering sequences the three
 * {@code ApplicationRunner}s in the collector profile; it says nothing about the
 * {@code @Scheduled} tick, which is not a runner and whose scheduling is enabled for the
 * whole profile.
 */
@SpringBootTest(
        classes = {AiJobApplication.class, CollectorImportStartupIsolationTest.RecordingExitConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        args = {
                "--aijob.import.jsonl=target/jdimport-isolation/batch.jsonl",
                "--aijob.import.manifest=target/jdimport-isolation/batch.manifest.json"
        },
        properties = {
                "spring.main.web-application-type=none",
                // The schema is created in this class's static initialiser, before Spring
                // exists. Neither of the usual mechanisms works here: @Sql runs before a
                // test METHOD, and the import under test runs during context startup,
                // which is earlier; and the URL's INIT= clause runs on EVERY connection,
                // so the pool opening a second one would re-run the script's DROPs and
                // delete what startup had just imported.
                // Must match SCHEMA_URL below. That they really are one database is not
                // taken on trust: the startup import writes through Spring's pool and
                // theImportRanDuringStartupAndTheProcessAskedToExitCleanly reads the row
                // back, so a drifted string fails that test rather than passing quietly.
                "spring.datasource.url=jdbc:h2:mem:importisolation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "aijob.storage.root=target/aijob-test-uploads",
                // Deliberately ON, to prove the tick is held off by the command mode and
                // not merely by collection being switched off.
                "aijob.collector.enabled=true",
                "aijob.collector.initial-delay-minutes=0"
        }
)
@ActiveProfiles("collector")
class CollectorImportStartupIsolationTest {

    private static final Path DIR = Path.of("target", "jdimport-isolation");
    private static final String RUN = "20260901T000000Z-isolation0";

    /**
     * The same connection string the annotation above sets, repeated because a class
     * cannot reference its own members from its own annotation.
     *
     * <p>{@code DB_CLOSE_DELAY=-1} keeps the in-memory database alive for the whole JVM,
     * which is what lets the static initialiser create the schema on its own connection
     * and have Spring's pool find it already there.
     */
    private static final String SCHEMA_URL =
            "jdbc:h2:mem:importisolation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

    // Both run when the class loads, which is before Spring builds the context and runs
    // the import: the batch files the arguments point at, and the schema it writes into.
    static {
        try (java.sql.Connection connection =
                     java.sql.DriverManager.getConnection(SCHEMA_URL, "sa", "test-only-h2-password");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("RUNSCRIPT FROM 'classpath:schema/job-catalog-h2.sql'");
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("could not create the isolation schema", ex);
        }
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve("batch.jsonl"),
                    "{\"run_id\":\"" + RUN + "\",\"provider\":\"greenhouse\",\"board\":\"isolation\","
                            + "\"external_id\":\"ISO-1\",\"title\":\"Isolation Probe\","
                            + "\"company\":\"Example\",\"location\":\"Remote\","
                            + "\"apply_url\":\"https://job-boards.greenhouse.io/isolation/jobs/1\","
                            + "\"description\":\"<p>A synthetic posting.</p>\",\"remote\":true,"
                            + "\"posted_at\":\"2026-08-18T18:06:19Z\","
                            + "\"fetched_at\":\"2026-09-01T16:17:00Z\"}\n",
                    StandardCharsets.UTF_8);
            Files.writeString(DIR.resolve("batch.manifest.json"), """
                    {
                      "run_id": "%s",
                      "mode": "live",
                      "provider": "greenhouse",
                      "board": "isolation",
                      "company": "Example",
                      "fetched_count": 1,
                      "exported_count": 1,
                      "truncated": false,
                      "complete_snapshot": true,
                      "status": "ok"
                    }
                    """.formatted(RUN), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CollectorCommandMode commandMode;

    @Autowired
    private JobCollectorScheduler scheduler;

    @Autowired
    private JobCatalogMapper jobCatalogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingExit exit;

    /**
     * Spying the ingest service is what makes "no outbound request" checkable: every board
     * read in this application goes through it, so a service that was never called is a
     * process that never reached an adapter, an HTTP client or a job board.
     */
    @MockitoSpyBean
    private JobIngestService jobIngestService;

    /**
     * The adapters themselves, watched directly. They are the only code in this
     * application that opens a connection to a job board, so a fetch count of zero is the
     * closest thing to a packet capture available in-process.
     */
    @MockitoSpyBean
    private GreenhouseBoardAdapter greenhouseBoardAdapter;

    @MockitoSpyBean
    private LeverBoardAdapter leverBoardAdapter;

    @Test
    void theImportRanDuringStartupAndTheProcessAskedToExitCleanly() {
        assertEquals(1, exit.calls.get(), "the import runner must end the process exactly once");
        assertEquals(0, exit.lastCode.get(), "a clean import exits 0");
        assertEquals(1, countCatalog(), "the batch really was imported");
    }

    @Test
    void noHttpServerIsStarted() {
        assertFalse(context instanceof org.springframework.web.context.WebApplicationContext,
                "an import process must not stand up a servlet context");
        assertTrue(context.getBeansOfType(
                        org.springframework.boot.web.servlet.server.ServletWebServerFactory.class).isEmpty(),
                "no web server factory should exist in this context");
    }

    @Test
    void theScheduledCollectionTickDoesNothingInAOneShotProcess() {
        assertTrue(commandMode.isOneShotCommand(),
                "import arguments must put this process in one-shot mode");
        // Collection is switched ON in this context, so the tick is held off by the mode
        // and by nothing else. Invoked directly rather than waited for, because the point
        // is what it does when it fires, not when.
        scheduler.collect();
        scheduler.collect();

        verify(jobIngestService, never()).collectDueSources();
        verify(jobIngestService, never()).collectSource(any());
    }

    @Test
    void noBoardWasEverRead() {
        // Nothing during startup — the import itself, the runners, the scheduler — reached
        // the ingest service, which is the only door to an outbound board request.
        verify(jobIngestService, never()).collectDueSources();
        verify(jobIngestService, never()).collectSource(any());
        verify(jobIngestService, never()).housekeeping();

        // And the adapters, which are the only things that would actually open a socket
        // to a job board, were never asked to fetch anything.
        verify(greenhouseBoardAdapter, never()).fetch(any());
        verify(leverBoardAdapter, never()).fetch(any());
    }

    @Test
    void theCollectorRunnerDidNotAlsoCollect() {
        // JobCollectorRunner carries no @Order, so it runs last of the three. It must not
        // have started a collection: no run-once flag was given, and the import runner
        // ends the process before it anyway.
        assertEquals(0, countIngestRunsWithStatus("SUCCESS"));
        assertEquals(1, countIngestRunsWithStatus("SAMPLE_IMPORT"));
    }

    @Test
    void theImportedBoardIsNotSwitchedOnForCrawling() {
        Integer enabled = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_sources WHERE enabled = TRUE OR crawl_allowed = TRUE",
                Integer.class);
        assertEquals(0, enabled, "importing a file must not opt a board into crawling");
    }

    private int countCatalog() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_catalog", Integer.class);
    }

    private int countIngestRunsWithStatus(String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_ingest_runs WHERE status = ?", Integer.class, status);
    }

    /** Records the exit instead of terminating the JVM the test is running in. */
    static final class RecordingExit implements CollectorProcessExit {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger lastCode = new AtomicInteger(-1);

        @Override
        public void exit(int code) {
            calls.incrementAndGet();
            lastCode.set(code);
        }
    }

    @TestConfiguration
    static class RecordingExitConfig {
        @Bean
        @Primary
        RecordingExit recordingExit() {
            return new RecordingExit();
        }
    }
}
