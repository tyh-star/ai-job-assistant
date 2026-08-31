package aijob.controller;

import aijob.dto.TailorChange;
import aijob.dto.TailoredResumeResponse;
import aijob.entity.TailorProposal;
import aijob.enums.TailorMode;
import aijob.exception.GlobalExceptionHandler;
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
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class JobControllerTailorProposalTest {

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
    void createTailorProposal_returns201PendingJsonWithoutFiles() throws Exception {
        TailorChange change = new TailorChange();
        change.setNodeId("p-23");
        change.setOriginalText("Developed rate limiting.");
        change.setNewText("Developed IP rate-limiting for production traffic.");
        change.setApplied(false);
        change.setRequiresConfirmation(true);
        change.setEvidenceType(TailorChange.EVIDENCE_VERIFIED);
        change.setEvidenceNodeIds(List.of("p-23"));
        change.setEvidenceQuote("Developed rate limiting.");

        TailoredResumeResponse response = new TailoredResumeResponse();
        response.setResumeId(3L);
        response.setJobId(10L);
        response.setMode(TailorMode.CONSERVATIVE);
        response.setProposalId(42L);
        response.setStatus(TailorProposal.STATUS_PENDING);
        response.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(24));
        response.setUnchanged(true);
        response.setAppliedChangeCount(0);
        response.setRejectedChangeCount(0);
        response.setRejectedChanges(List.of());
        response.setChanges(List.of(change));

        when(jobService.createTailorProposal(EMAIL, 10L, 3L, null)).thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-proposals", 10L)
                        .principal(authentication)
                        .param("resumeId", "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalId").value(42))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.mode").value("CONSERVATIVE"))
                .andExpect(jsonPath("$.resumeId").value(3))
                .andExpect(jsonPath("$.jobId").value(10))
                .andExpect(jsonPath("$.downloadUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.pdfDownloadUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.generatedFileName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.generatedFilePath").doesNotExist())
                .andExpect(jsonPath("$.appliedChangeCount").value(0))
                .andExpect(jsonPath("$.unchanged").value(true))
                .andExpect(jsonPath("$.changes[0].nodeId").value("p-23"))
                .andExpect(jsonPath("$.changes[0].originalText").value("Developed rate limiting."))
                .andExpect(jsonPath("$.changes[0].newText")
                        .value("Developed IP rate-limiting for production traffic."))
                .andExpect(jsonPath("$.changes[0].applied").value(false))
                .andExpect(jsonPath("$.changes[0].requiresConfirmation").value(true))
                .andExpect(jsonPath("$.changes[0].evidenceType").value("VERIFIED"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void oldTailorEndpoint_isUnchanged() throws Exception {
        TailoredResumeResponse response = new TailoredResumeResponse();
        response.setResumeId(3L);
        response.setJobId(10L);
        response.setMode(TailorMode.CONSERVATIVE);
        response.setDownloadUrl("/api/v1/jobs/10/tailored/out.docx");
        response.setStatus(TailoredResumeResponse.STATUS_APPLIED);
        when(jobService.tailorResume(EMAIL, 10L, 3L, TailorMode.CONSERVATIVE)).thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor", 10L)
                        .principal(authentication)
                        .param("resumeId", "3")
                        .param("mode", "CONSERVATIVE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.downloadUrl").value("/api/v1/jobs/10/tailored/out.docx"));

        verify(jobService, never()).createTailorProposal(EMAIL, 10L, 3L, null);
    }

    @Test
    void confirmTailorProposal_selectedNodeIdsOnly_isStillAccepted() throws Exception {
        TailoredResumeResponse response = new TailoredResumeResponse();
        response.setProposalId(42L);
        response.setStatus(TailorProposal.STATUS_CONFIRMED);
        response.setDownloadUrl("/api/v1/jobs/10/tailored/out.docx");
        when(jobService.confirmTailorProposal(eq(EMAIL), eq(10L), eq(42L), any(aijob.dto.ConfirmTailorProposalRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-proposals/{proposalId}/confirm", 10L, 42L)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedNodeIds\":[\"p-16\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.downloadUrl").value("/api/v1/jobs/10/tailored/out.docx"));

        org.mockito.ArgumentCaptor<aijob.dto.ConfirmTailorProposalRequest> captor =
                org.mockito.ArgumentCaptor.forClass(aijob.dto.ConfirmTailorProposalRequest.class);
        verify(jobService).confirmTailorProposal(eq(EMAIL), eq(10L), eq(42L), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("p-16"), captor.getValue().getSelectedNodeIds());
    }

    @Test
    void confirmTailorProposal_ignoresExtraEditedFieldsAndPassesNewTextOnly() throws Exception {
        TailoredResumeResponse response = new TailoredResumeResponse();
        response.setProposalId(42L);
        response.setStatus(TailorProposal.STATUS_CONFIRMED);
        when(jobService.confirmTailorProposal(eq(EMAIL), eq(10L), eq(42L), any(aijob.dto.ConfirmTailorProposalRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-proposals/{proposalId}/confirm", 10L, 42L)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "selectedNodeIds": ["p-16"],
                                  "editedChanges": [
                                    {
                                      "nodeId": "p-16",
                                      "newText": "Edited Spring Boot bullet.",
                                      "originalText": "should be ignored",
                                      "evidenceType": "VERIFIED"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());

        org.mockito.ArgumentCaptor<aijob.dto.ConfirmTailorProposalRequest> captor =
                org.mockito.ArgumentCaptor.forClass(aijob.dto.ConfirmTailorProposalRequest.class);
        verify(jobService).confirmTailorProposal(eq(EMAIL), eq(10L), eq(42L), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("p-16"), captor.getValue().getSelectedNodeIds());
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(new aijob.dto.EditedTailorChange("p-16", "Edited Spring Boot bullet.")),
                captor.getValue().getEditedChanges());
    }

    @Test
    void continueTailorProposal_returns200WithAddedCount() throws Exception {
        TailoredResumeResponse response = new TailoredResumeResponse();
        response.setResumeId(3L);
        response.setJobId(10L);
        response.setMode(TailorMode.CONSERVATIVE);
        response.setProposalId(42L);
        response.setStatus(TailorProposal.STATUS_PENDING);
        response.setUnchanged(true);
        response.setAppliedChangeCount(0);
        response.setRejectedChangeCount(0);
        response.setRejectedChanges(List.of());
        response.setChanges(List.of());
        response.setAddedSuggestionCount(2);
        response.setHasMoreSuggestions(true);
        response.setExploredNodeIds(List.of("p-16", "p-17"));

        when(jobService.continueTailorProposal(EMAIL, 10L, 42L)).thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-proposals/{proposalId}/continue", 10L, 42L)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addedSuggestionCount").value(2))
                .andExpect(jsonPath("$.hasMoreSuggestions").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(jobService).continueTailorProposal(EMAIL, 10L, 42L);
    }

    @Test
    void continueTailorProposal_otherUser_is404() throws Exception {
        when(jobService.continueTailorProposal(EMAIL, 10L, 42L))
                .thenThrow(new aijob.exception.TailorProposalNotFoundException("Tailor proposal not found"));

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-proposals/{proposalId}/continue", 10L, 42L)
                        .principal(authentication))
                .andExpect(status().isNotFound());
    }

    @Test
    void continueTailorProposal_confirmed_is409() throws Exception {
        when(jobService.continueTailorProposal(EMAIL, 10L, 42L))
                .thenThrow(new aijob.exception.TailorProposalConflictException(
                        "Tailor proposal is no longer pending"));

        mockMvc.perform(post("/api/v1/jobs/{jobId}/tailor-proposals/{proposalId}/continue", 10L, 42L)
                        .principal(authentication))
                .andExpect(status().isConflict());
    }
}
