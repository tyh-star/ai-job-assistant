package aijob.controller;

import aijob.dto.ResumeResponse;
import aijob.exception.GlobalExceptionHandler;
import aijob.exception.InvalidResumeFileException;
import aijob.exception.ResumeNotFoundException;
import aijob.service.ResumeService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ResumeControllerCanonicalizeTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private ResumeService resumeService;

    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ResumeController(resumeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        authentication = new UsernamePasswordAuthenticationToken(EMAIL, null);
    }

    @Test
    void canonicalize_returns201WithChildResume() throws Exception {
        ResumeResponse child = new ResumeResponse();
        child.setId(41L);
        child.setFileName("Yaohang_Tang_canonical.docx");
        child.setFileType("docx");
        child.setOrigin("TEMPLATE");
        child.setReviewStatus("READY");
        child.setParentResumeId(4L);
        child.setDownloadUrl("/api/v1/resumes/41/download");
        when(resumeService.canonicalizeResume(EMAIL, 4L)).thenReturn(child);

        mockMvc.perform(post("/api/v1/resumes/4/canonicalize").principal(authentication))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(41))
                .andExpect(jsonPath("$.fileType").value("docx"))
                .andExpect(jsonPath("$.origin").value("TEMPLATE"))
                .andExpect(jsonPath("$.reviewStatus").value("READY"))
                .andExpect(jsonPath("$.parentResumeId").value(4))
                .andExpect(jsonPath("$.downloadUrl").value("/api/v1/resumes/41/download"))
                .andExpect(jsonPath("$.filePath").doesNotExist());
    }

    @Test
    void canonicalize_nonPdf_returns400() throws Exception {
        when(resumeService.canonicalizeResume(EMAIL, 4L))
                .thenThrow(new InvalidResumeFileException("Canonicalize is only available for PDF resumes"));

        mockMvc.perform(post("/api/v1/resumes/4/canonicalize").principal(authentication))
                .andExpect(status().isBadRequest());
    }

    @Test
    void canonicalize_otherUser_returns404() throws Exception {
        when(resumeService.canonicalizeResume(EMAIL, 4L))
                .thenThrow(new ResumeNotFoundException("Resume not found"));

        mockMvc.perform(post("/api/v1/resumes/4/canonicalize").principal(authentication))
                .andExpect(status().isNotFound());
    }
}
