package aijob.collector;

import aijob.entity.JobCatalogEntry;
import aijob.entity.JobSource;
import aijob.mapper.JobCatalogMapper;
import aijob.mapper.JobSourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin CLI has to behave like a command, not like a server that happens to run a
 * command first: a status the shell can branch on, and a process that ends.
 */
class JobSourceAdminRunnerTest {

    private JobSourceMapper jobSourceMapper;
    private JobCatalogMapper jobCatalogMapper;
    private RecordingExit exit;
    private JobSourceAdminRunner runner;

    @BeforeEach
    void setUp() {
        jobSourceMapper = mock(JobSourceMapper.class);
        jobCatalogMapper = mock(JobCatalogMapper.class);
        exit = new RecordingExit();
        runner = new JobSourceAdminRunner(
                jobSourceMapper, jobCatalogMapper, new JobCollectorProperties(), exit);
    }

    @Test
    void anOrdinaryCollectorStartIsLeftAlone() {
        runner.run(new DefaultApplicationArguments());

        // No admin flag: this is the scheduled collector booting, and exiting here
        // would kill it.
        assertFalse(exit.called);
        verify(jobSourceMapper, never()).findAll();
    }

    @Test
    void listingSourcesSucceedsAndEndsTheProcess() {
        when(jobSourceMapper.findAll()).thenReturn(List.of());

        runner.run(new DefaultApplicationArguments("--aijob.sources.list=true"));

        // Previously the command printed its output and then sat there with the
        // scheduler alive, which is how a one-off admin call started collecting.
        assertTrue(exit.called, "an admin command must end the process");
        assertEquals(0, exit.status);
    }

    @Test
    void addingASourceCreatesItDisabledAndExitsZero() {
        List<JobSource> inserted = new ArrayList<>();
        when(jobSourceMapper.insert(any())).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        });

        runner.run(new DefaultApplicationArguments(
                "--aijob.sources.add=true",
                "--aijob.sources.provider=GREENHOUSE",
                "--aijob.sources.tenant=examplecorp",
                "--aijob.sources.display-name=Example Corp",
                "--aijob.sources.terms-url=https://example.com/terms"));

        assertEquals(0, exit.status);
        assertEquals(1, inserted.size());
        JobSource created = inserted.get(0);
        assertEquals("GREENHOUSE", created.getProviderType());
        assertEquals("examplecorp", created.getTenantKey());
        // Both opt-in flags stay off until a human has read the board's terms.
        assertFalse(created.isEnabled());
        assertFalse(created.isCrawlAllowed());
        assertEquals(JobCollectorClientConfig.GREENHOUSE_BASE_URL, created.getBaseUrl());
    }

    @Test
    void anUnknownProviderIsRejectedWithoutWriting() {
        runner.run(new DefaultApplicationArguments(
                "--aijob.sources.add=true",
                "--aijob.sources.provider=WORKDAY",
                "--aijob.sources.tenant=examplecorp",
                "--aijob.sources.display-name=Example Corp"));

        assertEquals(2, exit.status);
        verify(jobSourceMapper, never()).insert(any());
    }

    @Test
    void aTenantKeyThatIsNotABoardTokenIsRejected() {
        runner.run(new DefaultApplicationArguments(
                "--aijob.sources.add=true",
                "--aijob.sources.provider=GREENHOUSE",
                "--aijob.sources.tenant=https://evil.example.com/x",
                "--aijob.sources.display-name=Example Corp"));

        assertEquals(2, exit.status);
        verify(jobSourceMapper, never()).insert(any());
    }

    @Test
    void aMissingDisplayNameIsRejected() {
        runner.run(new DefaultApplicationArguments(
                "--aijob.sources.add=true",
                "--aijob.sources.provider=LEVER",
                "--aijob.sources.tenant=examplecorp"));

        assertEquals(2, exit.status);
        verify(jobSourceMapper, never()).insert(any());
    }

    @Test
    void aFailedInsertExitsNonZero() {
        doThrow(new IllegalStateException("duplicate")).when(jobSourceMapper).insert(any());

        runner.run(new DefaultApplicationArguments(
                "--aijob.sources.add=true",
                "--aijob.sources.provider=GREENHOUSE",
                "--aijob.sources.tenant=examplecorp",
                "--aijob.sources.display-name=Example Corp"));

        assertEquals(1, exit.status);
    }

    @Test
    void theDuplicateReportListsCandidatesAndExitsZero() {
        JobCatalogEntry first = catalogEntry(1L, "fingerprint-a");
        JobCatalogEntry second = catalogEntry(2L, "fingerprint-a");
        when(jobCatalogMapper.findRecentActive(anyInt())).thenReturn(List.of(first));
        when(jobCatalogMapper.findSuspectedDuplicates(anyString(), anyLong(), anyInt()))
                .thenReturn(List.of(second));

        runner.run(new DefaultApplicationArguments("--aijob.sources.duplicates=true"));

        assertEquals(0, exit.status);
        // It only reports. Nothing merges these; that is a human decision.
        verify(jobCatalogMapper, never()).update(any());
    }

    private static JobCatalogEntry catalogEntry(Long id, String fingerprint) {
        JobCatalogEntry entry = new JobCatalogEntry();
        entry.setId(id);
        entry.setFingerprint(fingerprint);
        entry.setCompanyName("Acme");
        entry.setTitle("Java Developer");
        entry.setLocationText("Toronto, ON");
        entry.setApplyUrl("https://boards.greenhouse.io/acme/jobs/" + id);
        return entry;
    }

    /** Records the status instead of terminating the JVM the test runs in. */
    private static final class RecordingExit implements CollectorProcessExit {

        private boolean called;
        private int status = -1;

        @Override
        public void exit(int code) {
            called = true;
            status = code;
        }
    }
}
