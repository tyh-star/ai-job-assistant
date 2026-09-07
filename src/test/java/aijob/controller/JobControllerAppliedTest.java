package aijob.controller;

import aijob.dto.JobResponse;
import aijob.exception.GlobalExceptionHandler;
import aijob.exception.JobNotFoundException;
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

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST}/{@code DELETE /api/v1/jobs/{id}/applied} -- confirming/revoking "applied"
 * on ANY personal job (manual, Greenhouse, a saved catalog posting, ...), not just a
 * catalog posting reached via {@code JobCatalogController}. Powers the Jobs page's
 * "已保存职位"/"已投递职位" tabs.
 *
 * <p>Standalone {@code MockMvc} over a mocked {@link JobService} -- same style as {@link
 * JobControllerBulkDeleteTest}. The route itself falling under {@code
 * anyRequest().authenticated()} (see {@code SecurityConfig}) is exercised by the real
 * security filter chain elsewhere; this class is about the controller's own translation
 * of {@link JobService#markApplied}/{@link JobService#clearApplied} into HTTP.
 */
@ExtendWith(MockitoExtension.class)
class JobControllerAppliedTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private JobService jobService;

    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new JobController(jobService, org.mockito.Mockito.mock(TailorAnalysisService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        authentication = new UsernamePasswordAuthenticationToken(EMAIL, null);
    }

    private static JobResponse job(long id, boolean applied, LocalDateTime appliedAt) {
        return new JobResponse(
                id, "Acme", "Engineer", "Remote", "https://example.com/job", "Ship things.",
                "MANUAL", "SAVED", null, applied, appliedAt,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    @Test
    void markApplied_success_returnsAppliedTrueWithAppliedAt() throws Exception {
        LocalDateTime appliedAt = LocalDateTime.of(2026, 9, 6, 12, 0);
        when(jobService.markApplied(EMAIL, 11L)).thenReturn(job(11L, true, appliedAt));

        mockMvc.perform(post("/api/v1/jobs/11/applied").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.appliedAt").exists());
    }

    @Test
    void markApplied_otherUsersJob_returns404() throws Exception {
        when(jobService.markApplied(EMAIL, 99L)).thenThrow(new JobNotFoundException("Job not found"));

        mockMvc.perform(post("/api/v1/jobs/99/applied").principal(authentication))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearApplied_success_returnsAppliedFalse() throws Exception {
        when(jobService.clearApplied(EMAIL, 11L)).thenReturn(job(11L, false, null));

        mockMvc.perform(delete("/api/v1/jobs/11/applied").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.appliedAt").doesNotExist());
    }

    @Test
    void clearApplied_otherUsersJob_returns404() throws Exception {
        when(jobService.clearApplied(EMAIL, 99L)).thenThrow(new JobNotFoundException("Job not found"));

        mockMvc.perform(delete("/api/v1/jobs/99/applied").principal(authentication))
                .andExpect(status().isNotFound());
    }

    @Test
    void markApplied_isCalledOncePerRequest_idempotencyLivesInTheServiceLayer() throws Exception {
        // The controller itself does no dedup -- JobServiceImplTest already proves
        // markApplied/clearApplied are idempotent at the mapper level (an UPDATE ...
        // WHERE id=? AND user_id=?, safe to repeat). This just confirms the controller
        // calls through exactly once per HTTP request, forwarding whatever the service
        // layer decides.
        when(jobService.markApplied(EMAIL, 11L)).thenReturn(job(11L, true, LocalDateTime.now()));

        mockMvc.perform(post("/api/v1/jobs/11/applied").principal(authentication)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/jobs/11/applied").principal(authentication)).andExpect(status().isOk());

        org.mockito.Mockito.verify(jobService, org.mockito.Mockito.times(2)).markApplied(eq(EMAIL), eq(11L));
    }
}
