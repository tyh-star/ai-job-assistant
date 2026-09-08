package aijob.controller;

import aijob.dto.ResumeResponse;
import aijob.dto.builder.CareerMaterialsResponse;
import aijob.dto.builder.CreateResumeBuilderDraftRequest;
import aijob.dto.builder.GenerateResumeBuilderDraftRequest;
import aijob.dto.builder.NormalizedResume;
import aijob.dto.builder.ResumeBuilderDraftResponse;
import aijob.dto.builder.ResumeBuilderDraftSummary;
import aijob.dto.builder.UpdateResumeBuilderDraftRequest;
import aijob.service.ResumeBuilderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 通用简历生成器 (resume builder). Every {@code /drafts/{id}/**} route enforces
 * ownership as {@code id + caller's own userId} together -- a draft belonging to
 * another account 404s exactly like one that does not exist; see
 * ResumeBuilderService's javadoc.
 */
@RestController
@RequestMapping("/api/v1/resume-builder")
public class ResumeBuilderController {

    private final ResumeBuilderService resumeBuilderService;

    public ResumeBuilderController(ResumeBuilderService resumeBuilderService) {
        this.resumeBuilderService = resumeBuilderService;
    }

    @PostMapping("/drafts")
    public ResponseEntity<ResumeBuilderDraftResponse> createDraft(
            Authentication authentication,
            @Valid @RequestBody CreateResumeBuilderDraftRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(resumeBuilderService.createDraft(authentication.getName(), request));
    }

    @GetMapping("/drafts")
    public ResponseEntity<List<ResumeBuilderDraftSummary>> listDrafts(Authentication authentication) {
        return ResponseEntity.ok(resumeBuilderService.listDrafts(authentication.getName()));
    }

    @GetMapping("/drafts/{id}")
    public ResponseEntity<ResumeBuilderDraftResponse> getDraft(
            Authentication authentication,
            @PathVariable Long id) {

        return ResponseEntity.ok(resumeBuilderService.getDraft(authentication.getName(), id));
    }

    @PutMapping("/drafts/{id}")
    public ResponseEntity<ResumeBuilderDraftResponse> updateDraft(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateResumeBuilderDraftRequest request) {

        return ResponseEntity.ok(resumeBuilderService.updateDraft(authentication.getName(), id, request));
    }

    @DeleteMapping("/drafts/{id}")
    public ResponseEntity<Void> deleteDraft(
            Authentication authentication,
            @PathVariable Long id) {

        resumeBuilderService.deleteDraft(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/drafts/{id}/duplicate")
    public ResponseEntity<ResumeBuilderDraftResponse> duplicateDraft(
            Authentication authentication,
            @PathVariable Long id) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(resumeBuilderService.duplicateDraft(authentication.getName(), id));
    }

    /**
     * Backend-computed, normalized preview -- the SAME model the DOCX writer renders
     * from, so this can never disagree with the generated file. POST (not GET) because
     * it always re-validates the draft's current saved content as a side effect of
     * building the preview.
     */
    @PostMapping("/drafts/{id}/preview")
    public ResponseEntity<NormalizedResume> previewDraft(
            Authentication authentication,
            @PathVariable Long id) {

        return ResponseEntity.ok(resumeBuilderService.previewDraft(authentication.getName(), id));
    }

    @PostMapping("/drafts/{id}/generate")
    public ResponseEntity<ResumeResponse> generateFromDraft(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody GenerateResumeBuilderDraftRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(resumeBuilderService.generateFromDraft(authentication.getName(), id, request));
    }

    /** Deterministic, zero-OpenAI-call optional materials -- never part of the formal
     * resume. See CareerMaterialsGenerator. */
    @GetMapping("/drafts/{id}/career-materials")
    public ResponseEntity<CareerMaterialsResponse> getCareerMaterials(
            Authentication authentication,
            @PathVariable Long id) {

        return ResponseEntity.ok(resumeBuilderService.getCareerMaterials(authentication.getName(), id));
    }
}
