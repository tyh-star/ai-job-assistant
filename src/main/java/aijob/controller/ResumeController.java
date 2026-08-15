package aijob.controller;

import aijob.dto.ResumeAnalysisResponse;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeResponse;
import aijob.service.ResumeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/resumes")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResumeResponse> uploadResume(
            Authentication authentication,
            @RequestParam("file") MultipartFile file) {

        ResumeResponse resume =
                resumeService.uploadResume(
                        authentication.getName(),
                        file
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(resume);
    }

    @GetMapping
    public ResponseEntity<List<ResumeResponse>> getMyResumes(
            Authentication authentication) {

        List<ResumeResponse> resumes =
                resumeService.getMyResumes(
                        authentication.getName()
                );

        return ResponseEntity.ok(resumes);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResumeResponse> getResume(
            Authentication authentication,
            @PathVariable Long id) {

        ResumeResponse resume =
                resumeService.getResume(
                        authentication.getName(),
                        id
                );

        return ResponseEntity.ok(resume);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteResume(
            Authentication authentication,
            @PathVariable Long id) {

        resumeService.deleteResume(
                authentication.getName(),
                id
        );

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/analyze")
    public ResponseEntity<ResumeAnalysisResponse> analyzeResume(
            Authentication authentication,
            @PathVariable Long id) {

        ResumeAnalysisResponse analysis =
                resumeService.analyzeResume(
                        authentication.getName(),
                        id
                );

        return ResponseEntity.ok(analysis);
    }

    @GetMapping("/{id}/document-nodes")
    public ResponseEntity<List<ResumeDocumentNode>> getDocumentNodes(
            Authentication authentication,
            @PathVariable Long id) {

        List<ResumeDocumentNode> nodes =
                resumeService.getDocumentNodes(
                        authentication.getName(),
                        id
                );

        return ResponseEntity.ok(nodes);
    }
}
