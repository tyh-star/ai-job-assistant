package aijob.controller;

import aijob.dto.ResumeEvidenceItemResponse;
import aijob.dto.SaveResumeEvidenceRequest;
import aijob.service.ResumeEvidenceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/resumes/{resumeId}/evidence")
public class ResumeEvidenceController {

    private final ResumeEvidenceService resumeEvidenceService;

    public ResumeEvidenceController(ResumeEvidenceService resumeEvidenceService) {
        this.resumeEvidenceService = resumeEvidenceService;
    }

    @GetMapping
    public ResponseEntity<List<ResumeEvidenceItemResponse>> list(
            Authentication authentication,
            @PathVariable Long resumeId) {

        return ResponseEntity.ok(resumeEvidenceService.list(authentication.getName(), resumeId));
    }

    @PostMapping
    public ResponseEntity<ResumeEvidenceItemResponse> save(
            Authentication authentication,
            @PathVariable Long resumeId,
            @RequestBody SaveResumeEvidenceRequest request) {

        ResumeEvidenceService.SaveResult result =
                resumeEvidenceService.save(authentication.getName(), resumeId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.item());
    }

    @DeleteMapping("/{evidenceId}")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable Long resumeId,
            @PathVariable Long evidenceId) {

        resumeEvidenceService.delete(authentication.getName(), resumeId, evidenceId);
        return ResponseEntity.noContent().build();
    }
}
