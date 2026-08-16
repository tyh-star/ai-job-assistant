package aijob.service.impl;

import aijob.client.GreenhouseJobClient;
import aijob.client.GreenhouseJobClient.GreenhouseBoardSnapshot;
import aijob.client.GreenhouseJobClient.GreenhouseJobPosting;
import aijob.dto.ImportJobsResponse;
import aijob.entity.Job;
import aijob.entity.User;
import aijob.exception.GreenhouseBoardNotFoundException;
import aijob.exception.GreenhouseServiceException;
import aijob.exception.InvalidGreenhouseBoardException;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceImplGreenhouseImportTest {

    private static final String EMAIL = "user@example.com";
    private static final String TOKEN = "airbnb";
    private static final String JOB_URL = "https://boards.greenhouse.io/airbnb/jobs/123";

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
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

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
                greenhouseJobClient
        );

        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
    }

    @Test
    void importGreenhouseJobs_validPosting_insertsMappedJob() {
        stubExistingJobs(List.of());
        stubInsertAssignsIds();
        when(greenhouseJobClient.fetchBoard(TOKEN)).thenReturn(new GreenhouseBoardSnapshot(
                "Airbnb",
                List.of(posting("123", "Software Engineer", JOB_URL, "<p>Build things</p>"))
        ));

        ImportJobsResponse response = jobService.importGreenhouseJobs(EMAIL, TOKEN);

        assertEquals(1, response.getImportedCount());
        assertEquals(0, response.getSkippedDuplicateCount());
        assertEquals(0, response.getSkippedNoTitleCount());
        assertEquals(0, response.getSkippedNoUrlCount());
        assertEquals(0, response.getSkippedNoDescriptionCount());
        assertEquals(0, response.getSkippedOverLimitCount());
        assertEquals(1, response.getJobs().size());
        assertEquals("Software Engineer", response.getJobs().get(0).getTitle());
        assertEquals("GREENHOUSE", response.getJobs().get(0).getSource());
        assertEquals("SAVED", response.getJobs().get(0).getStatus());
        assertEquals("123", response.getJobs().get(0).getExternalJobId());
        assertEquals("Build things", response.getJobs().get(0).getDescription());

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobMapper).insert(captor.capture());
        Job inserted = captor.getValue();
        assertEquals(1L, inserted.getUserId());
        assertEquals("Airbnb", inserted.getCompany());
        assertEquals("Software Engineer", inserted.getTitle());
        assertEquals("Remote", inserted.getLocation());
        assertEquals(JOB_URL, inserted.getJobUrl());
        assertEquals("Build things", inserted.getDescription());
        assertEquals("GREENHOUSE", inserted.getSource());
        assertEquals("SAVED", inserted.getStatus());
        assertEquals("123", inserted.getExternalJobId());
        verify(jobMapper, never()).update(any());
    }

    @Test
    void importGreenhouseJobs_officialBoardUrl_extractsTokenAndDoesNotRequestUserUrl() {
        stubExistingJobs(List.of());
        stubInsertAssignsIds();
        when(greenhouseJobClient.fetchBoard(TOKEN)).thenReturn(new GreenhouseBoardSnapshot(
                "Airbnb",
                List.of(posting("123", "Software Engineer", JOB_URL, "<p>Build things</p>"))
        ));

        ImportJobsResponse response = jobService.importGreenhouseJobs(
                EMAIL,
                "https://boards.greenhouse.io/airbnb"
        );

        assertEquals(1, response.getImportedCount());
        verify(greenhouseJobClient).fetchBoard(TOKEN);
        verify(greenhouseJobClient, never()).fetchBoard("https://boards.greenhouse.io/airbnb");
    }

    @Test
    void importGreenhouseJobs_duplicateExternalJobId_skipsWithoutUpdate() {
        Job existing = greenhouseJob("123", "https://boards.greenhouse.io/airbnb/jobs/old");
        stubExistingJobs(List.of(existing));
        when(greenhouseJobClient.fetchBoard(TOKEN)).thenReturn(new GreenhouseBoardSnapshot(
                "Airbnb",
                List.of(posting("123", "Software Engineer", JOB_URL, "<p>Build things</p>"))
        ));

        ImportJobsResponse response = jobService.importGreenhouseJobs(EMAIL, TOKEN);

        assertEquals(0, response.getImportedCount());
        assertEquals(1, response.getSkippedDuplicateCount());
        assertTrue(response.getJobs().isEmpty());
        verify(jobMapper, never()).insert(any());
        verify(jobMapper, never()).update(any());
    }

    @Test
    void importGreenhouseJobs_manualJobWithSameUrl_skipsWithoutUpdate() {
        Job existing = new Job();
        existing.setId(9L);
        existing.setUserId(1L);
        existing.setSource("MANUAL");
        existing.setJobUrl(JOB_URL);
        stubExistingJobs(List.of(existing));
        when(greenhouseJobClient.fetchBoard(TOKEN)).thenReturn(new GreenhouseBoardSnapshot(
                "Airbnb",
                List.of(posting("456", "Software Engineer", JOB_URL, "<p>Build things</p>"))
        ));

        ImportJobsResponse response = jobService.importGreenhouseJobs(EMAIL, TOKEN);

        assertEquals(0, response.getImportedCount());
        assertEquals(1, response.getSkippedDuplicateCount());
        verify(jobMapper, never()).insert(any());
        verify(jobMapper, never()).update(any());
    }

    @Test
    void importGreenhouseJobs_emptyContent_skipsAndCounts() {
        stubExistingJobs(List.of());
        when(greenhouseJobClient.fetchBoard(TOKEN)).thenReturn(new GreenhouseBoardSnapshot(
                "Airbnb",
                List.of(
                        posting("1", "Empty tags", JOB_URL + "/1", "<p></p>"),
                        posting("2", "Blank html", JOB_URL + "/2", "   "),
                        posting("3", "Null html", JOB_URL + "/3", null)
                )
        ));

        ImportJobsResponse response = jobService.importGreenhouseJobs(EMAIL, TOKEN);

        assertEquals(0, response.getImportedCount());
        assertEquals(3, response.getSkippedNoDescriptionCount());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void importGreenhouseJobs_partialSuccess_returnsFullStatsWithoutUpdating() {
        stubExistingJobs(List.of());
        stubInsertAssignsIds();
        when(greenhouseJobClient.fetchBoard(TOKEN)).thenReturn(new GreenhouseBoardSnapshot(
                "Airbnb",
                List.of(
                        posting("1", "Software Engineer", JOB_URL + "/1", "<p>Build things</p>"),
                        posting("2", null, JOB_URL + "/2", "<p>No title</p>"),
                        posting("3", "No url", null, "<p>Missing url</p>"),
                        posting("4", "No content", JOB_URL + "/4", "<p></p>")
                )
        ));

        ImportJobsResponse response = jobService.importGreenhouseJobs(EMAIL, TOKEN);

        assertEquals(1, response.getImportedCount());
        assertEquals(0, response.getSkippedDuplicateCount());
        assertEquals(1, response.getSkippedNoTitleCount());
        assertEquals(1, response.getSkippedNoUrlCount());
        assertEquals(1, response.getSkippedNoDescriptionCount());
        assertEquals(0, response.getSkippedOverLimitCount());
        assertEquals(1, response.getJobs().size());
        verify(jobMapper, times(1)).insert(any(Job.class));
        verify(jobMapper, never()).update(any());
    }

    @Test
    void importGreenhouseJobs_capsNewInsertsAtFifty() {
        stubExistingJobs(List.of());
        stubInsertAssignsIds();
        List<GreenhouseJobPosting> postings = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            postings.add(posting(
                    String.valueOf(i),
                    "Engineer " + i,
                    "https://boards.greenhouse.io/airbnb/jobs/" + i,
                    "<p>Role " + i + "</p>"
            ));
        }
        when(greenhouseJobClient.fetchBoard(TOKEN))
                .thenReturn(new GreenhouseBoardSnapshot("Airbnb", postings));

        ImportJobsResponse response = jobService.importGreenhouseJobs(EMAIL, TOKEN);

        assertEquals(50, response.getImportedCount());
        assertEquals(1, response.getSkippedOverLimitCount());
        assertEquals(50, response.getJobs().size());
        verify(jobMapper, times(50)).insert(any(Job.class));
        verify(jobMapper, never()).update(any());
    }

    @Test
    void importGreenhouseJobs_illegalToken_doesNotCallClient() {
        assertThrows(
                InvalidGreenhouseBoardException.class,
                () -> jobService.importGreenhouseJobs(EMAIL, "https://evil.example/airbnb")
        );
        assertThrows(
                InvalidGreenhouseBoardException.class,
                () -> jobService.importGreenhouseJobs(EMAIL, "not a token!")
        );

        verify(greenhouseJobClient, never()).fetchBoard(anyString());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void importGreenhouseJobs_boardNotFound_propagates404() {
        when(greenhouseJobClient.fetchBoard(TOKEN))
                .thenThrow(new GreenhouseBoardNotFoundException("Greenhouse board not found"));

        GreenhouseBoardNotFoundException ex = assertThrows(
                GreenhouseBoardNotFoundException.class,
                () -> jobService.importGreenhouseJobs(EMAIL, TOKEN)
        );

        assertEquals("Greenhouse board not found", ex.getMessage());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void importGreenhouseJobs_upstreamFailure_propagates502() {
        when(greenhouseJobClient.fetchBoard(TOKEN))
                .thenThrow(new GreenhouseServiceException("Greenhouse is temporarily unavailable"));

        assertThrows(
                GreenhouseServiceException.class,
                () -> jobService.importGreenhouseJobs(EMAIL, TOKEN)
        );
        verify(jobMapper, never()).insert(any());
    }

    private void stubExistingJobs(List<Job> jobs) {
        when(jobMapper.findByUserId(1L)).thenReturn(jobs);
    }

    private void stubInsertAssignsIds() {
        AtomicLong ids = new AtomicLong(100);
        when(jobMapper.insert(any(Job.class))).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            job.setId(ids.getAndIncrement());
            return 1;
        });
    }

    private static Job greenhouseJob(String externalJobId, String jobUrl) {
        Job job = new Job();
        job.setId(8L);
        job.setUserId(1L);
        job.setSource("GREENHOUSE");
        job.setExternalJobId(externalJobId);
        job.setJobUrl(jobUrl);
        return job;
    }

    private static GreenhouseJobPosting posting(
            String id,
            String title,
            String url,
            String html) {
        return new GreenhouseJobPosting(id, title, "Remote", url, html);
    }
}
