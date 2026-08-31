package aijob.controller;

import aijob.dto.ResumeEvidenceItemResponse;
import aijob.dto.SaveResumeEvidenceRequest;
import aijob.exception.EvidenceConflictException;
import aijob.exception.GlobalExceptionHandler;
import aijob.exception.InvalidEvidenceRequestException;
import aijob.exception.ResumeNotFoundException;
import aijob.service.ResumeEvidenceService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ResumeEvidenceControllerTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private ResumeEvidenceService resumeEvidenceService;

    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ResumeEvidenceController(resumeEvidenceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        authentication = new UsernamePasswordAuthenticationToken(EMAIL, null);
    }

    @Test
    void listReturns200() throws Exception {
        ResumeEvidenceItemResponse item = new ResumeEvidenceItemResponse();
        item.setId(12L);
        item.setTopic("Docker");
        when(resumeEvidenceService.list(EMAIL, 9L)).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/resumes/{resumeId}/evidence", 9L).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(12))
                .andExpect(jsonPath("$[0].topic").value("Docker"));
    }

    @Test
    void createReturns201AndUpdateReturns200() throws Exception {
        ResumeEvidenceItemResponse created = new ResumeEvidenceItemResponse();
        created.setId(12L);
        created.setTopic("Docker");
        when(resumeEvidenceService.save(eq(EMAIL), eq(9L), any()))
                .thenReturn(new ResumeEvidenceService.SaveResult(created, true))
                .thenReturn(new ResumeEvidenceService.SaveResult(created, false));

        String body = """
                {"topic":"Docker","category":"HARD_SKILL","answerStatus":"CONFIRMED_USED",
                 "entryKey":"PROJECT:prime-deals","relatedNodeIds":["p-16"],
                 "context":"Prime Deals","action":"Containerized the app","result":"Local env","confirmed":true}
                """;
        mockMvc.perform(post("/api/v1/resumes/{resumeId}/evidence", 9L)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12));
        mockMvc.perform(post("/api/v1/resumes/{resumeId}/evidence", 9L)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/resumes/{resumeId}/evidence/{evidenceId}", 9L, 12L)
                        .principal(authentication))
                .andExpect(status().isNoContent());
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        when(resumeEvidenceService.save(eq(EMAIL), eq(9L), any()))
                .thenThrow(new InvalidEvidenceRequestException("confirmed must be true"));
        mockMvc.perform(post("/api/v1/resumes/{resumeId}/evidence", 9L)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Docker\",\"confirmed\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidSemanticRequestReturns400ReasonCode() throws Exception {
        when(resumeEvidenceService.save(eq(EMAIL), eq(9L), any()))
                .thenThrow(new InvalidEvidenceRequestException("EVIDENCE_TOPIC_MISMATCH", null));
        mockMvc.perform(post("/api/v1/resumes/{resumeId}/evidence", 9L)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"debug","category":"RESPONSIBILITY","answerStatus":"CONFIRMED_USED",
                                 "entryKey":"GENERAL","relatedNodeIds":[],"action":"MVC architecture","confirmed":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("EVIDENCE_TOPIC_MISMATCH")));
    }

    @Test
    void evidenceConflictReturns409() throws Exception {
        when(resumeEvidenceService.save(eq(EMAIL), eq(9L), any()))
                .thenThrow(new EvidenceConflictException(
                        "You already saved evidence of using this skill. Delete the existing evidence before marking it as not used."));
        mockMvc.perform(post("/api/v1/resumes/{resumeId}/evidence", 9L)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"Docker","category":"HARD_SKILL","answerStatus":"CONFIRMED_NOT_USED",
                                 "entryKey":"GENERAL","relatedNodeIds":[],"confirmed":true}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already saved evidence")));
    }

    @Test
    void otherUsersResumeReturns404() throws Exception {
        doThrow(new ResumeNotFoundException("Resume not found"))
                .when(resumeEvidenceService).list(EMAIL, 9L);
        mockMvc.perform(get("/api/v1/resumes/{resumeId}/evidence", 9L).principal(authentication))
                .andExpect(status().isNotFound());
    }
}
