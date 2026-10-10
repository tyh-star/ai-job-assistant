package aijob.controller;

import aijob.dto.ResumeFileDownload;
import aijob.exception.GlobalExceptionHandler;
import aijob.exception.ResumeNotFoundException;
import aijob.service.ResumeService;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ResumeControllerDownloadTest {

    private static final String EMAIL = "user@example.com";
    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock
    private ResumeService resumeService;

    @TempDir
    Path tempDir;

    private ResumeController controller;
    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new ResumeController(resumeService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        authentication = new UsernamePasswordAuthenticationToken(EMAIL, null);
    }

    @Test
    void downloadResume_pdf_setsPdfHeadersAndBytes() throws Exception {
        Path file = tempDir.resolve("stored.pdf");
        byte[] payload = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);
        when(resumeService.downloadResume(EMAIL, 4L))
                .thenReturn(new ResumeFileDownload(file, "alex-morgan.pdf", "pdf"));

        ResponseEntity<Resource> response = controller.downloadResume(authentication, 4L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        ContentDisposition disposition = response.getHeaders().getContentDisposition();
        assertEquals("attachment", disposition.getType());
        assertEquals("alex-morgan.pdf", disposition.getFilename());
        assertArrayEquals(payload, response.getBody().getContentAsByteArray());
    }

    @Test
    void downloadResume_canonicalDocx_setsDocxHeadersAndBytes() throws Exception {
        Path file = tempDir.resolve("stored.docx");
        byte[] payload = "canonical-docx".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);
        when(resumeService.downloadResume(EMAIL, 41L))
                .thenReturn(new ResumeFileDownload(file, "Alex_Morgan_canonical.docx", "docx"));

        ResponseEntity<Resource> response = controller.downloadResume(authentication, 41L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.parseMediaType(DOCX_TYPE), response.getHeaders().getContentType());
        ContentDisposition disposition = response.getHeaders().getContentDisposition();
        assertEquals("attachment", disposition.getType());
        assertEquals("Alex_Morgan_canonical.docx", disposition.getFilename());
        assertArrayEquals(payload, response.getBody().getContentAsByteArray());
    }

    @Test
    void downloadResume_chineseName_usesUtf8FilenameStarWithAsciiFallback() throws Exception {
        Path file = tempDir.resolve("stored-cn.pdf");
        Files.write(file, "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        when(resumeService.downloadResume(EMAIL, 5L))
                .thenReturn(new ResumeFileDownload(file, "简历 (1).pdf", "pdf"));

        ResponseEntity<Resource> response = controller.downloadResume(authentication, 5L);

        String header = response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION);
        assertEquals("attachment; filename=\"_ (1).pdf\"; filename*=UTF-8''%E7%AE%80%E5%8E%86%20%281%29.pdf", header);
        assertEquals("简历 (1).pdf", response.getHeaders().getContentDisposition().getFilename());
    }

    @Test
    void downloadResume_otherUser_returns404() throws Exception {
        when(resumeService.downloadResume(EMAIL, 4L))
                .thenThrow(new ResumeNotFoundException("Resume not found"));

        mockMvc.perform(get("/api/v1/resumes/{id}/download", 4L).principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
