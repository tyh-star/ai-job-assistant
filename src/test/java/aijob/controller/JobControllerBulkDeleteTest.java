package aijob.controller;

import aijob.dto.BulkDeleteJobsResponse;
import aijob.exception.GlobalExceptionHandler;
import aijob.exception.InvalidBulkDeleteException;
import aijob.exception.JobNotFoundException;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class JobControllerBulkDeleteTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private JobService jobService;

    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new JobController(jobService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        authentication = new UsernamePasswordAuthenticationToken(EMAIL, null);
    }

    @Test
    void bulkDelete_success_returnsDeletedCount() throws Exception {
        when(jobService.bulkDeleteJobs(eq(EMAIL), eq(List.of(1L, 2L))))
                .thenReturn(new BulkDeleteJobsResponse(2));

        mockMvc.perform(delete("/api/v1/jobs/bulk")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobIds\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(2));
    }

    @Test
    void bulkDelete_empty_returns400() throws Exception {
        when(jobService.bulkDeleteJobs(eq(EMAIL), eq(List.of())))
                .thenThrow(new InvalidBulkDeleteException("Job IDs cannot be empty"));

        mockMvc.perform(delete("/api/v1/jobs/bulk")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkDelete_otherUser_returns404() throws Exception {
        when(jobService.bulkDeleteJobs(eq(EMAIL), eq(List.of(1L, 2L))))
                .thenThrow(new JobNotFoundException("Job not found"));

        mockMvc.perform(delete("/api/v1/jobs/bulk")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobIds\":[1,2]}"))
                .andExpect(status().isNotFound());
    }
}
