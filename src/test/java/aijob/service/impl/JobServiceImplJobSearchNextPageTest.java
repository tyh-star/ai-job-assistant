package aijob.service.impl;

import aijob.client.GreenhouseJobClient;
import aijob.config.JobSearchProperties;
import aijob.config.StoragePathResolver;
import aijob.dto.ImportSearchResultsRequest;
import aijob.dto.ImportSearchResultsResponse;
import aijob.dto.JobSearchRequest;
import aijob.dto.JobSearchResponse;
import aijob.entity.Job;
import aijob.entity.User;
import aijob.exception.JobSearchPageConflictException;
import aijob.exception.JobSearchRateLimitedException;
import aijob.exception.JobSearchSessionExpiredException;
import aijob.exception.JobSearchSessionNotFoundException;
import aijob.exception.JobSearchTimeoutException;
import aijob.exception.JobSearchUnavailableException;
import aijob.jobsearch.CaffeineProviderQueryCache;
import aijob.jobsearch.CaffeineSearchSessionStore;
import aijob.jobsearch.ExternalJobIds;
import aijob.jobsearch.JobSearchCapability;
import aijob.jobsearch.ProviderJobHit;
import aijob.jobsearch.ProviderSearchPage;
import aijob.jobsearch.SearchSession;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.JobSearchProvider;
import aijob.service.JobSearchProviderRegistry;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobServiceImplJobSearchNextPageTest {

    private static final String USER1 = "user1@example.com";
    private static final String USER2 = "user2@example.com";
    private static final String FULL_JD = "Design REST APIs and own production Java services in Toronto. "
            + "The team ships weekly, reviews incidents, and documents runbooks for on-call engineers.";
    private static final String PAGE2_JD = "Own Kubernetes clusters and CI pipelines for Java services. "
            + "This second-page role still has a complete job description for import. "
            + "The team runs production workloads, writes runbooks, and reviews incidents every week. "
            + "They also document deployment checklists, rotate on-call, and keep SLOs for Java APIs. "
            + "UNIQUE_PAGE2_TAIL_SHOULD_NOT_APPEAR_IN_SNIPPET_OR_LOGS";
    private static final String CURSOR = "raw-next-page-cursor-token";

    @Mock
    private JobMapper jobMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ResumeMapper resumeMapper;
    @Mock
    private TailorProposalMapper tailorProposalMapper;
    @Mock
    private AiResumeService aiResumeService;
    @Mock
    private ResumeDocumentService resumeDocumentService;
    @Mock
    private ResumeDocxTailorService resumeDocxTailorService;
    @Mock
    private GreenhouseJobClient greenhouseJobClient;
    @Mock
    private PdfExportService pdfExportService;
    @Mock
    private JobSearchProvider jsearchProvider;
    @Mock
    private Environment environment;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private MutableClock clock;
    private CaffeineProviderQueryCache queryCache;
    private CaffeineSearchSessionStore sessions;
    private JobServiceImpl jobService;
    private final List<Job> stored = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(JobServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        queryCache = new CaffeineProviderQueryCache();
        sessions = new CaffeineSearchSessionStore(clock);
        when(environment.getProperty("JOB_SEARCH_PRIMARY_PROVIDER")).thenReturn("JSEARCH");
        when(jsearchProvider.providerName()).thenReturn("JSEARCH");
        when(jsearchProvider.capability()).thenReturn(JobSearchCapability.FULL_DESCRIPTION);
        jobService = service(JobSearchProperties.defaults());
        stubUser(USER1, 1L);
        stubUser(USER2, 2L);
        when(jobMapper.findByUserId(any())).thenAnswer(invocation -> stored.stream()
                .filter(job -> job.getUserId().equals(invocation.getArgument(0)))
                .toList());
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(any(), any(), any())).thenAnswer(invocation -> stored.stream()
                .filter(job -> job.getUserId().equals(invocation.getArgument(0))
                        && invocation.getArgument(1).equals(job.getSource())
                        && invocation.getArgument(2).equals(job.getExternalJobId()))
                .findFirst()
                .orElse(null));
        when(jobMapper.insert(any(Job.class))).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            job.setId(ids.incrementAndGet());
            stored.add(copy(job));
            return 1;
        });
        when(jobMapper.findById(any())).thenAnswer(invocation -> stored.stream()
                .filter(job -> invocation.getArgument(0).equals(job.getId()))
                .findFirst()
                .orElse(null));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void firstPageStoresCursorAndCurrentPage() throws Exception {
        when(jsearchProvider.search(any())).thenReturn(new ProviderSearchPage(
                List.of(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")),
                true,
                null,
                CURSOR));

        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));
        SearchSession session = sessions.requireForUser(response.getSearchSessionId(), 1L);
        assertEquals(1, session.currentPage());
        assertEquals(CURSOR, session.nextCursor());
        String json = objectMapper.writeValueAsString(response);
        assertFalse(json.contains(CURSOR));
        assertFalse(json.contains("\"fullDescription\""));
        verify(jsearchProvider, times(1)).search(any());
        verify(jsearchProvider, never()).search(any(), any());
    }

    @Test
    void nextPageSendsServerCursorAndDoesNotReturnIt() throws Exception {
        UUID sessionId = searchFirstPage();
        when(jsearchProvider.search(any(), eq(CURSOR))).thenReturn(new ProviderSearchPage(
                List.of(hit("raw-2", "Platform Engineer", PAGE2_JD, "https://jobs.acme.com/k8s")),
                true,
                null,
                "second-cursor"));

        JobSearchResponse next = jobService.loadNextSearchPage(USER1, sessionId, 1);

        assertEquals(2, next.getPage());
        assertEquals("raw-2", next.getResults().get(0).getExternalJobId());
        assertTrue(PAGE2_JD.startsWith(next.getResults().get(0).getDescriptionSnippet()));
        assertEquals(2, next.getLoadedResultCount());
        String json = objectMapper.writeValueAsString(next);
        assertFalse(json.contains(CURSOR));
        assertFalse(json.contains("second-cursor"));
        assertFalse(json.contains("\"fullDescription\""));
        assertFalse(json.contains("UNIQUE_PAGE2_TAIL_SHOULD_NOT_APPEAR_IN_SNIPPET_OR_LOGS"));
        verify(jsearchProvider, times(1)).search(any(), eq(CURSOR));
        SearchSession session = sessions.requireForUser(sessionId, 1L);
        assertEquals(2, session.currentPage());
        assertEquals("second-cursor", session.nextCursor());
        assertEquals(PAGE2_JD, session.hitsByRawId().get("raw-2").fullDescription());
    }

    @Test
    void nextPageFullDescriptionCanBeImported() {
        UUID sessionId = searchFirstPage();
        when(jsearchProvider.search(any(), eq(CURSOR))).thenReturn(new ProviderSearchPage(
                List.of(hit("raw-2", "Platform Engineer", PAGE2_JD, "https://jobs.acme.com/k8s")),
                false,
                null,
                null));
        jobService.loadNextSearchPage(USER1, sessionId, 1);

        ImportSearchResultsResponse imported = jobService.importSearchResults(
                USER1, importRequest(sessionId, "raw-2"));
        assertEquals(1, imported.getImported().size());
        assertEquals(PAGE2_JD, stored.get(0).getDescription());
        assertEquals(ExternalJobIds.persistId("JSEARCH", "raw-2"), stored.get(0).getExternalJobId());
    }

    @Test
    void nextPageDedupesRawIdAndCanonicalUrlAndAllowsShortPages() {
        UUID sessionId = searchFirstPage();
        when(jsearchProvider.search(any(), eq(CURSOR))).thenReturn(new ProviderSearchPage(
                List.of(
                        hit("raw-1", "dup id", "https://jobs.acme.com/other"),
                        hit("raw-dup-url", "dup url", "https://jobs.acme.com/java?ref=2"),
                        hit("raw-2", "Platform Engineer", PAGE2_JD, "https://jobs.acme.com/k8s"),
                        hit("raw-fp", PAGE2_JD, "https://jobs.other.com/same-fp")
                ),
                true,
                null,
                "keep-going"));

        JobSearchResponse next = jobService.loadNextSearchPage(USER1, sessionId, 1);
        assertEquals(1, next.getResults().size());
        assertEquals("raw-2", next.getResults().get(0).getExternalJobId());
        assertEquals(2, next.getPage());
        assertEquals(2, next.getLoadedResultCount());
        assertTrue(next.getHasMore());
    }

    @Test
    void emptyUniquePageStillAdvancesWithoutAutoFetch() {
        UUID sessionId = searchFirstPage();
        when(jsearchProvider.search(any(), eq(CURSOR))).thenReturn(new ProviderSearchPage(
                List.of(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")),
                true,
                null,
                "after-empty"));

        JobSearchResponse next = jobService.loadNextSearchPage(USER1, sessionId, 1);
        assertEquals(2, next.getPage());
        assertTrue(next.getResults().isEmpty());
        assertTrue(next.getHasMore());
        verify(jsearchProvider, times(1)).search(any(), any());
        assertEquals(2, sessions.requireForUser(sessionId, 1L).currentPage());
    }

    @Test
    void missingCursorDoesNotCallUpstream() {
        when(jsearchProvider.search(any())).thenReturn(page(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")));
        JobSearchResponse first = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));

        JobSearchResponse next = jobService.loadNextSearchPage(USER1, first.getSearchSessionId(), 1);
        assertTrue(next.getResults().isEmpty());
        assertFalse(next.getHasMore());
        verify(jsearchProvider, never()).search(any(), any());
    }

    @Test
    void tenPageCapDoesNotCallUpstream() {
        jobService = service(new JobSearchProperties(10));
        when(jsearchProvider.search(any())).thenReturn(new ProviderSearchPage(
                List.of(hit("p1", FULL_JD, "https://jobs.acme.com/1")), true, null, "c1"));
        JobSearchResponse first = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));
        UUID sessionId = first.getSearchSessionId();
        String cursor = "c1";
        for (int page = 2; page <= 10; page++) {
            String nextCursor = page < 10 ? "c" + page : "unused";
            when(jsearchProvider.search(any(), eq(cursor))).thenReturn(new ProviderSearchPage(
                    List.of(hit("p" + page, "Role " + page, PAGE2_JD, "https://jobs.acme.com/" + page)),
                    true,
                    null,
                    nextCursor));
            JobSearchResponse next = jobService.loadNextSearchPage(USER1, sessionId, page - 1);
            assertEquals(page, next.getPage());
            cursor = nextCursor;
        }
        JobSearchResponse capped = jobService.loadNextSearchPage(USER1, sessionId, 10);
        assertFalse(capped.getHasMore());
        assertTrue(capped.getLimitReached());
        verify(jsearchProvider, times(9)).search(any(), any());
    }

    @Test
    void userIsolationAndExpiredSession() {
        UUID sessionId = searchFirstPage();
        assertThrows(JobSearchSessionNotFoundException.class,
                () -> jobService.loadNextSearchPage(USER2, sessionId, 1));
        clock.advance(Duration.ofMinutes(16));
        assertThrows(JobSearchSessionExpiredException.class,
                () -> jobService.loadNextSearchPage(USER1, sessionId, 1));
        verify(jsearchProvider, never()).search(any(), any());
    }

    @Test
    void expectedPageCannotSkipAndRepeatNextReturnsLoadedPage() {
        UUID sessionId = searchFirstPage();
        when(jsearchProvider.search(any(), eq(CURSOR))).thenReturn(new ProviderSearchPage(
                List.of(hit("raw-2", "Platform Engineer", PAGE2_JD, "https://jobs.acme.com/k8s")),
                true,
                null,
                "second"));
        assertThrows(JobSearchPageConflictException.class,
                () -> jobService.loadNextSearchPage(USER1, sessionId, 5));
        JobSearchResponse firstNext = jobService.loadNextSearchPage(USER1, sessionId, 1);
        JobSearchResponse repeat = jobService.loadNextSearchPage(USER1, sessionId, 1);
        assertEquals("raw-2", firstNext.getResults().get(0).getExternalJobId());
        assertEquals("raw-2", repeat.getResults().get(0).getExternalJobId());
        verify(jsearchProvider, times(1)).search(any(), any());
    }

    @Test
    void concurrentNextCallsUpstreamOnce() throws Exception {
        UUID sessionId = searchFirstPage();
        CountDownLatch started = new CountDownLatch(1);
        when(jsearchProvider.search(any(), eq(CURSOR))).thenAnswer(invocation -> {
            started.countDown();
            Thread.sleep(50);
            return new ProviderSearchPage(
                    List.of(hit("raw-2", "Platform Engineer", PAGE2_JD, "https://jobs.acme.com/k8s")),
                    false,
                    null,
                    null);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        Runnable task = () -> {
            try {
                jobService.loadNextSearchPage(USER1, sessionId, 1);
                successes.incrementAndGet();
            } finally {
                done.countDown();
            }
        };
        pool.submit(task);
        pool.submit(task);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(2, successes.get());
        verify(jsearchProvider, times(1)).search(any(), eq(CURSOR));
    }

    @Test
    void upstream429And502And504DoNotAdvanceCursor() {
        UUID sessionId = searchFirstPage();
        SearchSession before = sessions.requireForUser(sessionId, 1L);
        when(jsearchProvider.search(any(), eq(CURSOR)))
                .thenThrow(new JobSearchRateLimitedException("limited"));
        assertThrows(JobSearchRateLimitedException.class,
                () -> jobService.loadNextSearchPage(USER1, sessionId, 1));
        when(jsearchProvider.search(any(), eq(CURSOR)))
                .thenThrow(new JobSearchUnavailableException("down", "UPSTREAM_STATUS", 502));
        assertThrows(JobSearchUnavailableException.class,
                () -> jobService.loadNextSearchPage(USER1, sessionId, 1));
        when(jsearchProvider.search(any(), eq(CURSOR)))
                .thenThrow(new JobSearchTimeoutException("timeout"));
        assertThrows(JobSearchTimeoutException.class,
                () -> jobService.loadNextSearchPage(USER1, sessionId, 1));
        SearchSession after = sessions.requireForUser(sessionId, 1L);
        assertEquals(before.currentPage(), after.currentPage());
        assertEquals(before.nextCursor(), after.nextCursor());
        assertEquals(before.hits().size(), after.hits().size());
    }

    @Test
    void nextPageDoesNotRefreshTtl() {
        UUID sessionId = searchFirstPage();
        when(jsearchProvider.search(any(), eq(CURSOR))).thenReturn(new ProviderSearchPage(
                List.of(hit("raw-2", "Platform Engineer", PAGE2_JD, "https://jobs.acme.com/k8s")),
                false,
                null,
                null));
        clock.advance(Duration.ofMinutes(10));
        jobService.loadNextSearchPage(USER1, sessionId, 1);
        clock.advance(Duration.ofMinutes(6));
        assertThrows(JobSearchSessionExpiredException.class,
                () -> jobService.loadNextSearchPage(USER1, sessionId, 2));
    }

    @Test
    void logsHideCursorKeyAndFullDescription() throws Exception {
        UUID sessionId = searchFirstPage();
        when(jsearchProvider.search(any(), eq(CURSOR))).thenReturn(new ProviderSearchPage(
                List.of(hit("raw-2", "Platform Engineer", PAGE2_JD, "https://jobs.acme.com/k8s")),
                false,
                null,
                null));
        JobSearchResponse next = jobService.loadNextSearchPage(USER1, sessionId, 1);
        String logs = logs();
        String json = objectMapper.writeValueAsString(next);
        assertFalse(logs.contains(CURSOR), logs);
        assertFalse(logs.contains("UNIQUE_PAGE2_TAIL_SHOULD_NOT_APPEAR_IN_SNIPPET_OR_LOGS"), logs);
        assertFalse(logs.contains("x-api-key"), logs);
        assertFalse(json.contains(CURSOR));
        assertFalse(json.contains("UNIQUE_PAGE2_TAIL_SHOULD_NOT_APPEAR_IN_SNIPPET_OR_LOGS"));
    }

    private UUID searchFirstPage() {
        when(jsearchProvider.search(any())).thenReturn(new ProviderSearchPage(
                List.of(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")),
                true,
                null,
                CURSOR));
        return jobService.searchJobs(USER1, new JobSearchRequest("java", "Toronto", 7, 1, 10))
                .getSearchSessionId();
    }

    private JobServiceImpl service(JobSearchProperties properties) {
        JobSearchProviderRegistry registry = new JobSearchProviderRegistry(List.of(jsearchProvider), environment);
        return new JobServiceImpl(
                jobMapper,
                userMapper,
                resumeMapper,
                tailorProposalMapper,
                aiResumeService,
                resumeDocumentService,
                resumeDocxTailorService,
                objectMapper,
                greenhouseJobClient,
                pdfExportService,
                StoragePathResolver.forRoot(tempDir.resolve("uploads")),
                registry,
                queryCache,
                sessions,
                properties
        );
    }

    private String logs() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    private void stubUser(String email, long id) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        when(userMapper.findByEmail(email)).thenReturn(user);
    }

    private static ProviderSearchPage page(ProviderJobHit... hits) {
        return new ProviderSearchPage(List.of(hits), false, null);
    }

    private static ProviderJobHit hit(String rawId, String description, String url) {
        return hit(rawId, "Java Developer", description, url);
    }

    private static ProviderJobHit hit(String rawId, String title, String description, String url) {
        return new ProviderJobHit(
                rawId,
                title,
                "Acme",
                "Toronto",
                "2026-08-18T12:00:00Z",
                description,
                url,
                "LinkedIn",
                "JSEARCH");
    }

    private static ImportSearchResultsRequest importRequest(UUID sessionId, String... ids) {
        ImportSearchResultsRequest request = new ImportSearchResultsRequest();
        request.setSearchSessionId(sessionId);
        request.setSelectedExternalJobIds(List.of(ids));
        return request;
    }

    private static Job copy(Job job) {
        Job copy = new Job();
        copy.setId(job.getId());
        copy.setUserId(job.getUserId());
        copy.setCompany(job.getCompany());
        copy.setTitle(job.getTitle());
        copy.setLocation(job.getLocation());
        copy.setJobUrl(job.getJobUrl());
        copy.setDescription(job.getDescription());
        copy.setSource(job.getSource());
        copy.setStatus(job.getStatus());
        copy.setExternalJobId(job.getExternalJobId());
        return copy;
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
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
