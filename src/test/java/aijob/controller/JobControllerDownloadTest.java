package aijob.controller;

import aijob.exception.GlobalExceptionHandler;
import aijob.exception.JobNotFoundException;
import aijob.exception.TailoredFileNotFoundException;
import aijob.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class JobControllerDownloadTest {

    private static final String EMAIL = "user@example.com";
    private static final String FILE_NAME =
            "11111111-2222-3333-4444-555555555555.docx";
    private static final String PDF_NAME =
            "11111111-2222-3333-4444-555555555555.pdf";
    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock
    private JobService jobService;

    @TempDir
    Path tempDir;

    private JobController controller;
    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new JobController(jobService, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        authentication = new UsernamePasswordAuthenticationToken(EMAIL, null);
    }

    @Test
    void downloadTailoredResume_ownFile_setsDocxHeadersAndBytes() throws Exception {
        Path file = tempDir.resolve(FILE_NAME);
        byte[] payload = "tailored-docx".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);
        when(jobService.downloadTailoredResume(EMAIL, 10L, FILE_NAME)).thenReturn(file);

        ResponseEntity<Resource> response =
                controller.downloadTailoredResume(authentication, 10L, FILE_NAME);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.parseMediaType(DOCX_TYPE), response.getHeaders().getContentType());
        ContentDisposition disposition = response.getHeaders().getContentDisposition();
        assertEquals("attachment", disposition.getType());
        assertEquals(FILE_NAME, disposition.getFilename());
        assertArrayEquals(payload, response.getBody().getContentAsByteArray());
    }

    @Test
    void downloadTailoredResume_pdfFile_setsPdfContentType() throws Exception {
        Path file = tempDir.resolve(PDF_NAME);
        byte[] payload = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);
        when(jobService.downloadTailoredResume(EMAIL, 10L, PDF_NAME)).thenReturn(file);

        ResponseEntity<Resource> response =
                controller.downloadTailoredResume(authentication, 10L, PDF_NAME);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        ContentDisposition disposition = response.getHeaders().getContentDisposition();
        assertEquals("attachment", disposition.getType());
        assertEquals(PDF_NAME, disposition.getFilename());
        assertArrayEquals(payload, response.getBody().getContentAsByteArray());
    }

    @Test
    void downloadTailoredResume_otherUsersJob_returns404() throws Exception {
        when(jobService.downloadTailoredResume(EMAIL, 99L, FILE_NAME))
                .thenThrow(new JobNotFoundException("Job not found"));

        mockMvc.perform(get("/api/v1/jobs/{jobId}/tailored/{fileName}", 99L, FILE_NAME)
                        .principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void downloadTailoredResume_illegalFileName_returns404() throws Exception {
        when(jobService.downloadTailoredResume(EMAIL, 10L, "not-a-uuid.docx"))
                .thenThrow(new TailoredFileNotFoundException("Tailored file not found"));

        mockMvc.perform(get("/api/v1/jobs/{jobId}/tailored/{fileName}", 10L, "not-a-uuid.docx")
                        .principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void downloadTailoredResume_illegalExtension_returns404() throws Exception {
        when(jobService.downloadTailoredResume(
                EMAIL, 10L, "11111111-2222-3333-4444-555555555555.exe"))
                .thenThrow(new TailoredFileNotFoundException("Tailored file not found"));

        mockMvc.perform(get(
                        "/api/v1/jobs/{jobId}/tailored/{fileName}",
                        10L,
                        "11111111-2222-3333-4444-555555555555.exe")
                        .principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void downloadTailoredResume_missingFile_returns404() throws Exception {
        when(jobService.downloadTailoredResume(EMAIL, 10L, FILE_NAME))
                .thenThrow(new TailoredFileNotFoundException("Tailored file not found"));

        mockMvc.perform(get("/api/v1/jobs/{jobId}/tailored/{fileName}", 10L, FILE_NAME)
                        .principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
