package aijob.controller;

import aijob.dto.TailorAnalysisDimensions;
import aijob.dto.TailorAnalysisResponse;
import aijob.dto.TailorAnalysisSummary;
import aijob.exception.GlobalExceptionHandler;
import aijob.exception.JobNotFoundException;
import aijob.exception.ResumeTextNotAvailableException;
import aijob.service.JobService;
import aijob.service.TailorAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class JobControllerTailorAnalysisTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private JobService jobService;
    @Mock
    private TailorAnalysisService tailorAnalysisService;

    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new JobController(jobService, tailorAnalysisService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        authentication = new UsernamePasswordAuthenticationToken(EMAIL, null);
    }

    @Test
    void analyze_success_returns200() throws Exception {
        TailorAnalysisResponse response = new TailorAnalysisResponse();
        response.setResumeId(9L);
        response.setJobId(153L);
        response.setOverallScore(68);
        response.setDimensions(new TailorAnalysisDimensions(80, 65, 70, 45, 72));
        response.setRequirements(List.of());
        response.setSummary(new TailorAnalysisSummary(3, 1, 1, 5, 2, 1, 1, 4, 2));
        when(tailorAnalysisService.analyze(EMAIL, 153L, 9L)).thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-analysis", 153L)
                        .principal(authentication)
                        .param("resumeId", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumeId").value(9))
                .andExpect(jsonPath("$.jobId").value(153))
                .andExpect(jsonPath("$.overallScore").value(68))
                .andExpect(jsonPath("$.dimensions.hardSkills").value(80));
    }

    @Test
    void refreshProfile_success_returns200() throws Exception {
        TailorAnalysisResponse response = new TailorAnalysisResponse();
        response.setResumeId(9L);
        response.setJobId(153L);
        response.setOverallScore(59);
        response.setRequirementsFixed(true);
        response.setProfileUpdatedAt("2026-08-18T23:00:00");
        when(tailorAnalysisService.refreshProfile(EMAIL, 153L, 9L)).thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-analysis/profile/refresh", 153L)
                        .principal(authentication)
                        .param("resumeId", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(59))
                .andExpect(jsonPath("$.requirementsFixed").value(true))
                .andExpect(jsonPath("$.profileUpdatedAt").value("2026-08-18T23:00:00"));
    }

    @Test
    void refreshProfile_otherUsersJob_returns404() throws Exception {
        when(tailorAnalysisService.refreshProfile(EMAIL, 153L, 9L))
                .thenThrow(new JobNotFoundException("Job not found"));

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-analysis/profile/refresh", 153L)
                        .principal(authentication)
                        .param("resumeId", "9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void analyze_otherUsersJob_returns404() throws Exception {
        when(tailorAnalysisService.analyze(EMAIL, 153L, 9L))
                .thenThrow(new JobNotFoundException("Job not found"));

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-analysis", 153L)
                        .principal(authentication)
                        .param("resumeId", "9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void analyze_blankResumeText_returns422() throws Exception {
        when(tailorAnalysisService.analyze(EMAIL, 153L, 9L))
                .thenThrow(new ResumeTextNotAvailableException("Resume text is not available for analysis"));

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-analysis", 153L)
                        .principal(authentication)
                        .param("resumeId", "9"))
                .andExpect(status().isUnprocessableEntity());
    }
}
