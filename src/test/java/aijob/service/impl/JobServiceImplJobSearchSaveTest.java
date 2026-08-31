package aijob.service.impl;

import aijob.client.GreenhouseJobClient;
import aijob.config.StoragePathResolver;
import aijob.dto.SaveJobSearchResultRequest;
import aijob.dto.SaveJobSearchResultResponse;
import aijob.entity.Job;
import aijob.entity.User;
import aijob.exception.InvalidJobSearchException;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceImplJobSearchSaveTest {

    private static final String EMAIL = "user@example.com";
    private static final String SNIPPET = "Build APIs with Java.";
    private static final String FULL_JD = """
            We are hiring a Java developer.
            You will design REST APIs, own production incidents,
            and collaborate with product managers in Toronto.
            """;
    private static final String SOURCE_URL = "https://www.adzuna.ca/land/ad/123";

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
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private JobServiceImpl jobService;

    @BeforeEach
    void setUp() {
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
                StoragePathResolver.forRoot(tempDir.resolve("uploads"))
        );
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
    }

    @Test
    void saveSearchResult_blankFullDescription_doesNotInsert() {
        SaveJobSearchResultRequest request = saveRequest("   ");
        InvalidJobSearchException ex = assertThrows(
                InvalidJobSearchException.class,
                () -> jobService.saveSearchResult(EMAIL, request));
        assertEquals("Full job description is required", ex.getMessage());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void saveSearchResult_snippetCannotBeSavedAsAnalyzableJob() {
        SaveJobSearchResultRequest request = saveRequest(SNIPPET);
        InvalidJobSearchException ex = assertThrows(
                InvalidJobSearchException.class,
                () -> jobService.saveSearchResult(EMAIL, request));
        assertEquals("Search snippets cannot be saved as the job description", ex.getMessage());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void saveSearchResult_fullDescription_insertsAdzunaJob() {
        when(jobMapper.findByUserId(1L)).thenReturn(List.of());
        stubInsertAssignsIds();

        SaveJobSearchResultResponse response = jobService.saveSearchResult(EMAIL, saveRequest(FULL_JD));

        assertTrue(response.isCreated());
        assertEquals("ADZUNA", response.getJob().getSource());
        assertEquals(FULL_JD.strip(), response.getJob().getDescription());
        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobMapper).insert(captor.capture());
        Job inserted = captor.getValue();
        assertEquals(FULL_JD.strip(), inserted.getDescription());
        assertFalse(inserted.getDescription().equals(SNIPPET));
        assertEquals(SOURCE_URL, inserted.getJobUrl());
        assertEquals("123", inserted.getExternalJobId());
        assertEquals("SAVED", inserted.getStatus());
    }

    @Test
    void saveSearchResult_duplicateExternalId_returnsExistingWithoutInsert() {
        Job existing = savedAdzunaJob();
        when(jobMapper.findByUserId(1L)).thenReturn(List.of(existing));

        SaveJobSearchResultResponse response = jobService.saveSearchResult(EMAIL, saveRequest(FULL_JD));

        assertFalse(response.isCreated());
        assertEquals(8L, response.getJob().getId());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void saveSearchResult_duplicateUrl_returnsExistingWithoutInsert() {
        Job existing = savedAdzunaJob();
        existing.setExternalJobId("other");
        existing.setJobUrl(SOURCE_URL + "/");
        when(jobMapper.findByUserId(1L)).thenReturn(List.of(existing));

        SaveJobSearchResultResponse response = jobService.saveSearchResult(EMAIL, saveRequest(FULL_JD));

        assertFalse(response.isCreated());
        verify(jobMapper, never()).insert(any());
    }

    private void stubInsertAssignsIds() {
        AtomicLong ids = new AtomicLong(100);
        when(jobMapper.insert(any(Job.class))).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            job.setId(ids.getAndIncrement());
            return 1;
        });
    }

    private static SaveJobSearchResultRequest saveRequest(String fullDescription) {
        SaveJobSearchResultRequest request = new SaveJobSearchResultRequest();
        request.setExternalJobId("123");
        request.setTitle("Java Developer");
        request.setCompany("Acme");
        request.setLocation("Toronto");
        request.setDescriptionSnippet(SNIPPET);
        request.setSource("ADZUNA");
        request.setSourceUrl(SOURCE_URL);
        request.setFullDescription(fullDescription);
        return request;
    }

    private static Job savedAdzunaJob() {
        Job job = new Job();
        job.setId(8L);
        job.setUserId(1L);
        job.setCompany("Acme");
        job.setTitle("Java Developer");
        job.setLocation("Toronto");
        job.setJobUrl(SOURCE_URL);
        job.setDescription(FULL_JD.strip());
        job.setSource("ADZUNA");
        job.setStatus("SAVED");
        job.setExternalJobId("123");
        return job;
    }
}
