package aijob.controller;

import aijob.dto.ImportJobsResponse;
import aijob.dto.JobResponse;
import aijob.exception.GlobalExceptionHandler;
import aijob.exception.GreenhouseBoardNotFoundException;
import aijob.exception.GreenhouseServiceException;
import aijob.exception.InvalidGreenhouseBoardException;
import aijob.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class JobControllerGreenhouseImportTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private JobService jobService;

    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new JobController(jobService, null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        authentication = new UsernamePasswordAuthenticationToken(EMAIL, null);
    }

    @Test
    void importGreenhouseJobs_partialSuccess_returns200WithStats() throws Exception {
        JobResponse job = new JobResponse(
                100L,
                "Airbnb",
                "Software Engineer",
                "Remote",
                "https://boards.greenhouse.io/airbnb/jobs/123",
                "Build things",
                "GREENHOUSE",
                "SAVED",
                "123",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        when(jobService.importGreenhouseJobs(EMAIL, "airbnb")).thenReturn(new ImportJobsResponse(
                1, 1, 0, 0, 1, 0, List.of(job)
        ));

        mockMvc.perform(post("/api/v1/jobs/imports/greenhouse")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardToken\":\"airbnb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.skippedDuplicateCount").value(1))
                .andExpect(jsonPath("$.skippedNoDescriptionCount").value(1))
                .andExpect(jsonPath("$.skippedOverLimitCount").value(0))
                .andExpect(jsonPath("$.jobs[0].source").value("GREENHOUSE"))
                .andExpect(jsonPath("$.jobs[0].externalJobId").value("123"));
    }

    @Test
    void importGreenhouseJobs_invalidBoard_returns400() throws Exception {
        when(jobService.importGreenhouseJobs(EMAIL, "https://evil.example/airbnb"))
                .thenThrow(new InvalidGreenhouseBoardException("Invalid Greenhouse board URL"));

        mockMvc.perform(post("/api/v1/jobs/imports/greenhouse")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardToken\":\"https://evil.example/airbnb\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void importGreenhouseJobs_boardNotFound_returns404() throws Exception {
        when(jobService.importGreenhouseJobs(EMAIL, "missing"))
                .thenThrow(new GreenhouseBoardNotFoundException("Greenhouse board not found"));

        mockMvc.perform(post("/api/v1/jobs/imports/greenhouse")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardToken\":\"missing\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void importGreenhouseJobs_upstreamError_returns502() throws Exception {
        when(jobService.importGreenhouseJobs(EMAIL, "airbnb"))
                .thenThrow(new GreenhouseServiceException("timeout"));

        mockMvc.perform(post("/api/v1/jobs/imports/greenhouse")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardToken\":\"airbnb\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").value("Greenhouse is temporarily unavailable"));
    }

    @Test
    void importGreenhouseJobs_blankToken_returns400WithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/imports/greenhouse")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardToken\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(jobService, never()).importGreenhouseJobs(anyString(), anyString());
    }
}
