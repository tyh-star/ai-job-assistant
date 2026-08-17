package aijob.controller;

import aijob.dto.ResumeResponse;
import aijob.exception.GlobalExceptionHandler;
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

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ResumeControllerListDeleteTest {

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
    void listResumes_default_doesNotRequestDerived() throws Exception {
        ResumeResponse upload = new ResumeResponse();
        upload.setId(4L);
        upload.setOrigin("UPLOAD");
        upload.setFileAvailable(true);
        upload.setDownloadUrl("/api/v1/resumes/4/download");
        when(resumeService.getMyResumes(EMAIL, false)).thenReturn(List.of(upload));

        mockMvc.perform(get("/api/v1/resumes").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(4))
                .andExpect(jsonPath("$[0].fileAvailable").value(true))
                .andExpect(jsonPath("$[0].downloadUrl").value("/api/v1/resumes/4/download"))
                .andExpect(jsonPath("$[0].filePath").doesNotExist());

        verify(resumeService).getMyResumes(EMAIL, false);
    }

    @Test
    void listResumes_includeDerivedTrue_requestsDerived() throws Exception {
        when(resumeService.getMyResumes(EMAIL, true)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/resumes").param("includeDerived", "true").principal(authentication))
                .andExpect(status().isOk());

        verify(resumeService).getMyResumes(EMAIL, true);
    }

    @Test
    void deleteFamily_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/resumes/4/family").principal(authentication))
                .andExpect(status().isNoContent());

        verify(resumeService).deleteResumeFamily(EMAIL, 4L);
    }
}
