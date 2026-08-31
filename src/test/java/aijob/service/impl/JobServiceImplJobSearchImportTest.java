package aijob.service.impl;

import aijob.client.GreenhouseJobClient;
import aijob.config.StoragePathResolver;
import aijob.dto.ImportSearchResultsRequest;
import aijob.dto.ImportSearchResultsResponse;
import aijob.dto.JobSearchRequest;
import aijob.dto.JobSearchResponse;
import aijob.entity.Job;
import aijob.entity.User;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobSearchSessionExpiredException;
import aijob.exception.JobSearchSessionNotFoundException;
import aijob.jobsearch.CaffeineProviderQueryCache;
import aijob.jobsearch.CaffeineSearchSessionStore;
import aijob.jobsearch.ExternalJobIds;
import aijob.jobsearch.JobSearchCapability;
import aijob.jobsearch.ProviderJobHit;
import aijob.jobsearch.ProviderSearchPage;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobServiceImplJobSearchImportTest {

    private static final String USER1 = "user1@example.com";
    private static final String USER2 = "user2@example.com";
    private static final String FULL_JD = "Design REST APIs and own production Java services in Toronto. "
            + "The team ships weekly, reviews incidents, and documents runbooks for on-call engineers. "
            + "Candidates should have Spring Boot experience and comfort with relational databases.";
    private static final String LONG_RAW_ID =
            "abc-very-long-raw-id-that-exceeds-sixty-four-characters-xxxxxxxxxxxxxxxxxxxx";

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

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        queryCache = new CaffeineProviderQueryCache();
        sessions = new CaffeineSearchSessionStore(clock);
        when(environment.getProperty("JOB_SEARCH_PRIMARY_PROVIDER")).thenReturn("JSEARCH");
        when(jsearchProvider.providerName()).thenReturn("JSEARCH");
        when(jsearchProvider.capability()).thenReturn(JobSearchCapability.FULL_DESCRIPTION);
        JobSearchProviderRegistry registry = new JobSearchProviderRegistry(List.of(jsearchProvider), environment);
        jobService = new JobServiceImpl(
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
                sessions
        );
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

    @Test
    void search_returnsPreviewOnlyAndKeepsFullDescriptionInSession() throws Exception {
        when(jsearchProvider.search(any())).thenReturn(page(hit(LONG_RAW_ID, FULL_JD, "https://jobs.acme.com/java")));

        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", "Toronto", 7, 1, 10));

        assertEquals("JSEARCH", response.getProvider());
        assertNull(response.getTotalCount());
        assertEquals(LONG_RAW_ID, response.getResults().get(0).getExternalJobId());
        String snippet = response.getResults().get(0).getDescriptionSnippet();
        assertTrue(FULL_JD.startsWith(snippet));
        assertTrue(snippet.length() <= JobServiceImpl.DESCRIPTION_SNIPPET_CHARS);
        assertTrue(response.getResults().get(0).getFullDescriptionAvailable());
        String json = objectMapper.writeValueAsString(response);
        assertFalse(json.contains("job_description"));
        assertFalse(json.contains("\"fullDescription\""));
        verify(jobMapper, never()).insert(any());
        verify(jsearchProvider, times(1)).search(any());

        ImportSearchResultsRequest importRequest = importRequest(response.getSearchSessionId(), LONG_RAW_ID);
        ImportSearchResultsResponse imported = jobService.importSearchResults(USER1, importRequest);
        assertEquals(1, imported.getImported().size());
        assertEquals(ExternalJobIds.persistId("JSEARCH", LONG_RAW_ID), stored.get(0).getExternalJobId());
        assertEquals(64, stored.get(0).getExternalJobId().length());
        assertEquals(FULL_JD, stored.get(0).getDescription());
    }

    @Test
    void search_marksAlreadySavedUsingHashedExternalId() {
        Job existing = new Job();
        existing.setId(8L);
        existing.setUserId(1L);
        existing.setCompany("Acme");
        existing.setTitle("Java Developer");
        existing.setLocation("Toronto");
        existing.setJobUrl("https://jobs.acme.com/java");
        existing.setDescription(FULL_JD);
        existing.setSource("JSEARCH");
        existing.setStatus("SAVED");
        existing.setExternalJobId(ExternalJobIds.persistId("JSEARCH", "raw-1"));
        stored.add(existing);
        when(jsearchProvider.search(any())).thenReturn(page(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")));

        JobSearchResponse alreadySaved = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));

        assertTrue(alreadySaved.getResults().get(0).getAlreadySaved());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void search_storesCursorOnSessionAndDoesNotReturnItToFrontend() throws Exception {
        when(jsearchProvider.search(any())).thenReturn(new ProviderSearchPage(
                List.of(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")),
                true,
                null,
                "next-page-token"));

        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));

        assertTrue(response.getHasMore());
        assertNull(response.getTotalCount());
        assertEquals("next-page-token", sessions.requireForUser(response.getSearchSessionId(), 1L).nextCursor());
        String json = objectMapper.writeValueAsString(response);
        assertFalse(json.contains("next-page-token"));
        assertFalse(json.contains("job_description"));
        assertFalse(json.contains("\"fullDescription\""));
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void search_sameQueryUsesCacheAndCreatesIsolatedSessions() {
        AtomicInteger searches = new AtomicInteger();
        when(jsearchProvider.search(any())).thenAnswer(invocation -> {
            searches.incrementAndGet();
            return page(hit("raw-1", FULL_JD, "https://jobs.acme.com/java"));
        });
        JobSearchRequest request = new JobSearchRequest("java", "Toronto", 7, 1, 10);

        JobSearchResponse first = jobService.searchJobs(USER1, request);
        JobSearchResponse secondUser = jobService.searchJobs(USER2, request);
        JobSearchResponse firstAgain = jobService.searchJobs(USER1, request);

        assertEquals(1, searches.get());
        assertNotEquals(first.getSearchSessionId(), secondUser.getSearchSessionId());
        assertNotEquals(first.getSearchSessionId(), firstAgain.getSearchSessionId());
    }

    @Test
    void import_user2CannotUseUser1Session() {
        when(jsearchProvider.search(any())).thenReturn(page(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")));
        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));

        assertThrows(JobSearchSessionNotFoundException.class,
                () -> jobService.importSearchResults(USER2, importRequest(response.getSearchSessionId(), "raw-1")));
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void import_expiredSessionReturns410() {
        when(jsearchProvider.search(any())).thenReturn(page(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")));
        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));
        clock.advance(Duration.ofMinutes(16));

        assertThrows(JobSearchSessionExpiredException.class,
                () -> jobService.importSearchResults(USER1, importRequest(response.getSearchSessionId(), "raw-1")));
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void import_forgedIdDoesNotWrite() {
        when(jsearchProvider.search(any())).thenReturn(page(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")));
        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));

        ImportSearchResultsResponse imported = jobService.importSearchResults(
                USER1, importRequest(response.getSearchSessionId(), "forged"));

        assertEquals("UNKNOWN_JOB", imported.getFailed().get(0).getReason());
        verify(jobMapper, never()).insert(any());
        verify(greenhouseJobClient, never()).fetchJob(any(), any());
    }

    @Test
    void import_partialSuccessAndIdempotentExisting() {
        when(jsearchProvider.search(any())).thenReturn(new ProviderSearchPage(List.of(
                hit("ok", FULL_JD, "https://jobs.acme.com/ok"),
                hit("missing", "", "https://example.com/unknown"),
                hit("again", FULL_JD, "https://jobs.acme.com/again")
        ), false, null));
        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));
        UUID sessionId = response.getSearchSessionId();

        ImportSearchResultsResponse first = jobService.importSearchResults(USER1, importRequest(sessionId, "ok", "missing"));
        assertEquals(1, first.getImported().size());
        assertEquals(1, first.getFailed().size());
        assertEquals("DESCRIPTION_MISSING", first.getFailed().get(0).getReason());

        ImportSearchResultsResponse second = jobService.importSearchResults(USER1, importRequest(sessionId, "ok", "again"));
        assertEquals(1, second.getExisting().size());
        assertEquals("ok", second.getExisting().get(0).getExternalJobId());
        assertEquals(1, second.getImported().size());
        assertEquals(2, stored.size());
        verify(greenhouseJobClient, never()).fetchBoard(any());
    }

    @Test
    void import_emptyOrTooManyIds_400() {
        when(jsearchProvider.search(any())).thenReturn(page(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")));
        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));
        assertThrows(InvalidJobSearchException.class,
                () -> jobService.importSearchResults(USER1, importRequest(response.getSearchSessionId())));
        ImportSearchResultsRequest tooMany = new ImportSearchResultsRequest();
        tooMany.setSearchSessionId(response.getSearchSessionId());
        tooMany.setSelectedExternalJobIds(java.util.stream.IntStream.rangeClosed(1, 21).mapToObj(i -> "id" + i).toList());
        assertThrows(InvalidJobSearchException.class, () -> jobService.importSearchResults(USER1, tooMany));
    }

    @Test
    void import_greenhouseOfficialUrlFillsMissingDescription() {
        when(jsearchProvider.search(any())).thenReturn(page(hit(
                "gh-1",
                "",
                "https://boards.greenhouse.io/airbnb/jobs/123")));
        when(greenhouseJobClient.fetchJob("airbnb", "123")).thenReturn(
                new GreenhouseJobClient.GreenhouseJobPosting("123", "Java", "Toronto",
                        "https://boards.greenhouse.io/airbnb/jobs/123",
                        "<p>Official Greenhouse description</p>"));
        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));

        ImportSearchResultsResponse imported = jobService.importSearchResults(
                USER1, importRequest(response.getSearchSessionId(), "gh-1"));

        assertEquals(1, imported.getImported().size());
        assertEquals("Official Greenhouse description", stored.get(0).getDescription());
        verify(greenhouseJobClient).fetchJob("airbnb", "123");
        verify(greenhouseJobClient, never()).fetchBoard(any());
    }

    @Test
    void import_unknownSiteIsNotFetched() {
        when(jsearchProvider.search(any())).thenReturn(page(hit("x", "", "https://evil.example/jobs/1")));
        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));

        ImportSearchResultsResponse imported = jobService.importSearchResults(
                USER1, importRequest(response.getSearchSessionId(), "x"));

        assertEquals("DESCRIPTION_MISSING", imported.getFailed().get(0).getReason());
        verify(greenhouseJobClient, never()).fetchJob(any(), any());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void import_concurrentDoesNotDuplicateRows() throws Exception {
        when(jsearchProvider.search(any())).thenReturn(page(hit("raw-1", FULL_JD, "https://jobs.acme.com/java")));
        JobSearchResponse response = jobService.searchJobs(USER1, new JobSearchRequest("java", null, null, 1, 10));
        Set<String> persistIds = ConcurrentHashMap.newKeySet();
        AtomicInteger inserts = new AtomicInteger();
        when(jobMapper.insert(any(Job.class))).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            synchronized (persistIds) {
                if (!persistIds.add(job.getExternalJobId())) {
                    throw new DuplicateKeyException("duplicate");
                }
                inserts.incrementAndGet();
                job.setId(ids.incrementAndGet());
                stored.add(copy(job));
                return 1;
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<ImportSearchResultsResponse> left = new AtomicReference<>();
        AtomicReference<ImportSearchResultsResponse> right = new AtomicReference<>();
        Runnable task = () -> {
            try {
                ImportSearchResultsResponse result = jobService.importSearchResults(
                        USER1, importRequest(response.getSearchSessionId(), "raw-1"));
                if (!left.compareAndSet(null, result)) {
                    right.set(result);
                }
            } finally {
                done.countDown();
            }
        };
        pool.submit(task);
        pool.submit(task);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(1, inserts.get());
        assertEquals(1, persistIds.size());
        int imported = size(left.get().getImported()) + size(right.get().getImported());
        int existing = size(left.get().getExisting()) + size(right.get().getExisting());
        assertEquals(2, imported + existing);
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
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
        return new ProviderJobHit(
                rawId,
                "Java Developer",
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
