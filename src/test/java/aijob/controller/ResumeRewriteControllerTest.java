package aijob.controller;

import aijob.exception.GlobalExceptionHandler;
import aijob.rewrite.FreeResumeRewriteService;
import aijob.rewrite.ProResumeRewriteService;
import aijob.rewrite.ai.RewriteAiException;
import aijob.rewrite.dto.FreeRewriteResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResumeRewriteControllerTest {

    @TempDir
    Path tempDir;

    private FreeResumeRewriteService free;
    private ProResumeRewriteService pro;
    private MockMvc mockMvc;
    private final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("alex@example.com", null);

    @BeforeEach
    void setUp() {
        free = mock(FreeResumeRewriteService.class);
        pro = mock(ProResumeRewriteService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ResumeRewriteController(free, pro))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()),
                        new ResourceHttpMessageConverter())
                .build();
    }

    @Test
    void freeAndProAreDifferentEndpointsAndServices() throws Exception {
        when(free.rewrite("alex@example.com", 12L)).thenReturn(new FreeRewriteResponse(12L, "openai/gpt-oss-20b",
                List.of(), 0, 0, true, "/api/v1/resume-rewrites/free/files/x.docx", "简历_rewrite.docx"));
        mockMvc.perform(post("/api/v1/resume-rewrites/free").principal(auth)
                        .contentType("application/json").content("{\"resumeId\":12}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-20b"));
        verifyNoInteractions(pro);

        mockMvc.perform(post("/api/v1/resume-rewrites/pro/sessions").principal(auth)
                .contentType("application/json").content("{\"resumeId\":12,\"jobId\":30}"));
        verify(pro).start(eq("alex@example.com"), any());
        verify(free, never()).download(any(), any());
    }

    @Test
    void proRejectsAMissingJob() throws Exception {
        mockMvc.perform(post("/api/v1/resume-rewrites/pro/sessions").principal(auth)
                        .contentType("application/json").content("{\"resumeId\":12}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(pro);
    }

    @Test
    void aiErrorsCarryStableCodes() throws Exception {
        when(free.rewrite(any(), any())).thenThrow(RewriteAiException.rateLimited());
        mockMvc.perform(post("/api/v1/resume-rewrites/free").principal(auth)
                        .contentType("application/json").content("{\"resumeId\":12}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("AI_RATE_LIMITED"));

        doThrow(RewriteAiException.timeout()).when(free).rewrite(any(), any());
        mockMvc.perform(post("/api/v1/resume-rewrites/free").principal(auth)
                        .contentType("application/json").content("{\"resumeId\":12}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("AI_TIMEOUT"));

        doThrow(RewriteAiException.upstream(500)).when(free).rewrite(any(), any());
        mockMvc.perform(post("/api/v1/resume-rewrites/free").principal(auth)
                        .contentType("application/json").content("{\"resumeId\":12}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"));
    }

    @Test
    void downloadUsesAUnicodeFileName() throws Exception {
        Path file = tempDir.resolve("r12-x.docx");
        Files.writeString(file, "docx");
        when(free.download("alex@example.com", "r12-x.docx"))
                .thenReturn(new FreeResumeRewriteService.DownloadFile(file, "简历_rewrite.docx"));
        mockMvc.perform(get("/api/v1/resume-rewrites/free/files/r12-x.docx").principal(auth))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"_rewrite.docx\"; filename*=UTF-8''%E7%AE%80%E5%8E%86_rewrite.docx"));
    }
}
