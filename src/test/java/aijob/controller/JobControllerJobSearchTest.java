package aijob.controller;

import aijob.dto.ImportSearchResultsResponse;
import aijob.dto.JobResponse;
import aijob.dto.JobSearchRequest;
import aijob.dto.JobSearchResponse;
import aijob.dto.JobSearchResult;
import aijob.dto.SaveJobSearchResultResponse;
import aijob.exception.GlobalExceptionHandler;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobSearchPageConflictException;
import aijob.exception.JobSearchRateLimitedException;
import aijob.exception.JobSearchSessionExpiredException;
import aijob.exception.JobSearchSessionNotFoundException;
import aijob.exception.JobSearchTimeoutException;
import aijob.exception.JobSearchUnavailableException;
import aijob.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class JobControllerJobSearchTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private JobService jobService;

    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new JobController(jobService, null, null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        authentication = new UsernamePasswordAuthenticationToken(EMAIL, null);
    }

    @Test
    void searchJobs_returnsResultsWithoutCountryOrSecrets() throws Exception {
        JobSearchResult result = new JobSearchResult(
                "123",
                "Java Developer",
                "Acme",
                "Toronto",
                "2026-08-01T12:00:00Z",
                "Build APIs",
                "JSEARCH",
                "https://jobs.acme.com/java",
                "LinkedIn",
                true,
                false
        );
        UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(jobService.searchJobs(eq(EMAIL), any(JobSearchRequest.class)))
                .thenReturn(new JobSearchResponse(sessionId, "JSEARCH", 1, 10, true, null, List.of(result)));

        mockMvc.perform(get("/api/v1/jobs/search")
                        .principal(authentication)
                        .param("keywords", "java")
                        .param("location", "Toronto")
                        .param("postedWithinDays", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.searchSessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.provider").value("JSEARCH"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.totalCount").doesNotExist())
                .andExpect(jsonPath("$.results[0].title").value("Java Developer"))
                .andExpect(jsonPath("$.results[0].source").value("JSEARCH"))
                .andExpect(jsonPath("$.results[0].publisher").value("LinkedIn"))
                .andExpect(jsonPath("$.results[0].fullDescriptionAvailable").value(true))
                .andExpect(jsonPath("$.results[0].job_description").doesNotExist())
                .andExpect(jsonPath("$.results[0].fullDescription").doesNotExist())
                .andExpect(jsonPath("$.results[0].country").doesNotExist())
                .andExpect(jsonPath("$.app_id").doesNotExist())
                .andExpect(jsonPath("$.app_key").doesNotExist())
                .andExpect(jsonPath("$..country").doesNotExist());

        ArgumentCaptor<JobSearchRequest> captor = ArgumentCaptor.forClass(JobSearchRequest.class);
        verify(jobService).searchJobs(eq(EMAIL), captor.capture());
        assertEquals("java", captor.getValue().keywords());
        assertEquals("Toronto", captor.getValue().location());
        assertEquals(7, captor.getValue().postedWithinDays());
    }

    @Test
    void searchJobs_blankKeywords_returns400() throws Exception {
        when(jobService.searchJobs(eq(EMAIL), any(JobSearchRequest.class)))
                .thenThrow(new InvalidJobSearchException("Search keywords are required"));

        mockMvc.perform(get("/api/v1/jobs/search")
                        .principal(authentication)
                        .param("keywords", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Search keywords are required"));
    }

    @Test
    void searchJobs_rateLimited_returns429() throws Exception {
        when(jobService.searchJobs(eq(EMAIL), any(JobSearchRequest.class)))
                .thenThrow(new JobSearchRateLimitedException("limited"));

        mockMvc.perform(get("/api/v1/jobs/search")
                        .principal(authentication)
                        .param("keywords", "java"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Job search rate limit reached. Try again later."));
    }

    @Test
    void searchJobs_timeout_returns504() throws Exception {
        when(jobService.searchJobs(eq(EMAIL), any(JobSearchRequest.class)))
                .thenThrow(new JobSearchTimeoutException("timeout"));

        mockMvc.perform(get("/api/v1/jobs/search")
                        .principal(authentication)
                        .param("keywords", "java"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.message").value("Job search timed out."));
    }

    @Test
    void searchJobs_unavailable_returns502() throws Exception {
        when(jobService.searchJobs(eq(EMAIL), any(JobSearchRequest.class)))
                .thenThrow(new JobSearchUnavailableException("down"));

        mockMvc.perform(get("/api/v1/jobs/search")
                        .principal(authentication)
                        .param("keywords", "java"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Job search is temporarily unavailable."));
    }

    @Test
    void saveSearchResult_missingFullDescription_returns400WithoutService() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/search-results/save")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalJobId": "123",
                                  "title": "Java Developer",
                                  "company": "Acme",
                                  "source": "ADZUNA",
                                  "sourceUrl": "https://www.adzuna.ca/land/ad/123",
                                  "descriptionSnippet": "Build APIs"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(jobService, never()).saveSearchResult(any(), any());
    }

    @Test
    void saveSearchResult_snippetAsDescription_returns400() throws Exception {
        when(jobService.saveSearchResult(eq(EMAIL), any()))
                .thenThrow(new InvalidJobSearchException(
                        "Search snippets cannot be saved as the job description"));

        mockMvc.perform(post("/api/v1/jobs/search-results/save")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalJobId": "123",
                                  "title": "Java Developer",
                                  "company": "Acme",
                                  "source": "ADZUNA",
                                  "sourceUrl": "https://www.adzuna.ca/land/ad/123",
                                  "descriptionSnippet": "Build APIs",
                                  "fullDescription": "Build APIs"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Search snippets cannot be saved as the job description"));
    }

    @Test
    void saveSearchResult_firstSave_returns201CreatedTrue() throws Exception {
        when(jobService.saveSearchResult(eq(EMAIL), any()))
                .thenReturn(new SaveJobSearchResultResponse(true, job(42L, "Full JD")));

        mockMvc.perform(post("/api/v1/jobs/search-results/save")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullSaveBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.job.id").value(42))
                .andExpect(jsonPath("$.job.description").value("Full JD"))
                .andExpect(jsonPath("$.job.source").value("ADZUNA"));
    }

    @Test
    void importSearchResults_returnsImportedExistingFailed() throws Exception {
        ImportSearchResultsResponse response = new ImportSearchResultsResponse();
        response.getImported().add(new ImportSearchResultsResponse.ImportedSearchJob("raw-1", 101L));
        response.getExisting().add(new ImportSearchResultsResponse.ImportedSearchJob("raw-2", 88L));
        response.getFailed().add(new ImportSearchResultsResponse.FailedSearchImport("raw-3", "DESCRIPTION_MISSING"));
        when(jobService.importSearchResults(eq(EMAIL), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs/search-results/import")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "searchSessionId": "11111111-1111-1111-1111-111111111111",
                                  "selectedExternalJobIds": ["raw-1", "raw-2", "raw-3"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported[0].jobId").value(101))
                .andExpect(jsonPath("$.existing[0].jobId").value(88))
                .andExpect(jsonPath("$.failed[0].reason").value("DESCRIPTION_MISSING"));
    }

    @Test
    void importSearchResults_expiredSession_returns410() throws Exception {
        when(jobService.importSearchResults(eq(EMAIL), any()))
                .thenThrow(new JobSearchSessionExpiredException("Search session has expired"));

        mockMvc.perform(post("/api/v1/jobs/search-results/import")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "searchSessionId": "11111111-1111-1111-1111-111111111111",
                                  "selectedExternalJobIds": ["raw-1"]
                                }
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("Search session has expired"));
    }

    @Test
    void importSearchResults_missingSession_returns404() throws Exception {
        when(jobService.importSearchResults(eq(EMAIL), any()))
                .thenThrow(new JobSearchSessionNotFoundException("Search session was not found"));

        mockMvc.perform(post("/api/v1/jobs/search-results/import")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "searchSessionId": "11111111-1111-1111-1111-111111111111",
                                  "selectedExternalJobIds": ["raw-1"]
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void nextSearchPage_returnsPageWithoutCursor() throws Exception {
        UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        JobSearchResult result = new JobSearchResult(
                "raw-2",
                "Java Developer",
                "Acme",
                "Toronto",
                "2026-08-01T12:00:00Z",
                "Build APIs",
                "JSEARCH",
                "https://jobs.acme.com/java-2",
                "LinkedIn",
                true,
                false
        );
        when(jobService.loadNextSearchPage(eq(EMAIL), eq(sessionId), eq(1)))
                .thenReturn(new JobSearchResponse(sessionId, "JSEARCH", 2, 10, true, null, List.of(result), false, 11));

        mockMvc.perform(post("/api/v1/jobs/search-sessions/{searchSessionId}/next", sessionId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPage\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.limitReached").value(false))
                .andExpect(jsonPath("$.loadedResultCount").value(11))
                .andExpect(jsonPath("$.results[0].externalJobId").value("raw-2"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.results[0].fullDescription").doesNotExist());
    }

    @Test
    void nextSearchPage_conflict_returns409() throws Exception {
        UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(jobService.loadNextSearchPage(eq(EMAIL), eq(sessionId), eq(5)))
                .thenThrow(new JobSearchPageConflictException("Search page state conflict"));

        mockMvc.perform(post("/api/v1/jobs/search-sessions/{searchSessionId}/next", sessionId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPage\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Search page state conflict"));
    }

    @Test
    void saveSearchResult_duplicate_returns200CreatedFalse() throws Exception {
        when(jobService.saveSearchResult(eq(EMAIL), any()))
                .thenReturn(new SaveJobSearchResultResponse(false, job(42L, "Full JD")));

        mockMvc.perform(post("/api/v1/jobs/search-results/save")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullSaveBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.job.id").value(42));
    }

    private static String fullSaveBody() {
        return """
                {
                  "externalJobId": "123",
                  "title": "Java Developer",
                  "company": "Acme",
                  "location": "Toronto",
                  "source": "ADZUNA",
                  "sourceUrl": "https://www.adzuna.ca/land/ad/123",
                  "descriptionSnippet": "Build APIs",
                  "fullDescription": "We need a Java developer to build APIs and own production systems."
                }
                """;
    }

    private static JobResponse job(Long id, String description) {
        return new JobResponse(
                id,
                "Acme",
                "Java Developer",
                "Toronto",
                "https://www.adzuna.ca/land/ad/123",
                description,
                "ADZUNA",
                "SAVED",
                "123",
                false,
                null,
                LocalDateTime.of(2026, 8, 1, 12, 0),
                LocalDateTime.of(2026, 8, 1, 12, 0)
        );
    }
}
