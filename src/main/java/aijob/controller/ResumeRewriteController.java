package aijob.controller;

import aijob.rewrite.FreeResumeRewriteService;
import aijob.rewrite.ProResumeRewriteService;
import aijob.rewrite.dto.FreeRewriteRequest;
import aijob.rewrite.dto.FreeRewriteResponse;
import aijob.rewrite.dto.ProRewriteRequests;
import aijob.rewrite.dto.ProRewriteSessionResponse;
import aijob.util.ResumeFileNames;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resume Rewrite (简历改写). Two separate user workflows:
 * <ul>
 *   <li>Free: {@code POST /free} with a resume only -- one request, returns the file.</li>
 *   <li>Pro: a session per resume + job -- {@code POST /pro/sessions} (analyze),
 *   {@code /answers}, {@code /preview}, {@code /confirm}; the file exists only after confirm.</li>
 * </ul>
 * The separate job-match endpoints under /api/v1/jobs stay as they are; Free never calls them.
 */
@RestController
@RequestMapping("/api/v1/resume-rewrites")
public class ResumeRewriteController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final FreeResumeRewriteService freeService;
    private final ProResumeRewriteService proService;

    public ResumeRewriteController(FreeResumeRewriteService freeService, ProResumeRewriteService proService) {
        this.freeService = freeService;
        this.proService = proService;
    }

    @PostMapping("/free")
    public ResponseEntity<FreeRewriteResponse> rewriteFree(
            Authentication authentication,
            @Valid @RequestBody FreeRewriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(freeService.rewrite(authentication.getName(), request.resumeId()));
    }

    @GetMapping("/free/files/{fileName}")
    public ResponseEntity<Resource> downloadFree(Authentication authentication, @PathVariable String fileName) {
        return file(freeService.download(authentication.getName(), fileName));
    }

    @PostMapping("/pro/sessions")
    public ResponseEntity<ProRewriteSessionResponse> startPro(
            Authentication authentication,
            @Valid @RequestBody ProRewriteRequests.Start request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(proService.start(authentication.getName(), request));
    }

    @GetMapping("/pro/sessions/{sessionId}")
    public ResponseEntity<ProRewriteSessionResponse> getPro(Authentication authentication, @PathVariable Long sessionId) {
        return ResponseEntity.ok(proService.get(authentication.getName(), sessionId));
    }

    @PostMapping("/pro/sessions/{sessionId}/answers")
    public ResponseEntity<ProRewriteSessionResponse> answerPro(
            Authentication authentication,
            @PathVariable Long sessionId,
            @Valid @RequestBody ProRewriteRequests.Answers request) {
        return ResponseEntity.ok(proService.answer(authentication.getName(), sessionId, request));
    }

    @PostMapping("/pro/sessions/{sessionId}/preview")
    public ResponseEntity<ProRewriteSessionResponse> previewPro(Authentication authentication, @PathVariable Long sessionId) {
        return ResponseEntity.ok(proService.preview(authentication.getName(), sessionId));
    }

    @PostMapping("/pro/sessions/{sessionId}/confirm")
    public ResponseEntity<ProRewriteSessionResponse> confirmPro(
            Authentication authentication,
            @PathVariable Long sessionId,
            @Valid @RequestBody ProRewriteRequests.Confirm request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(proService.confirm(authentication.getName(), sessionId, request));
    }

    @GetMapping("/pro/sessions/{sessionId}/file")
    public ResponseEntity<Resource> downloadPro(Authentication authentication, @PathVariable Long sessionId) {
        return file(proService.download(authentication.getName(), sessionId));
    }

    private static ResponseEntity<Resource> file(FreeResumeRewriteService.DownloadFile download) {
        return ResponseEntity.ok()
                .contentType(DOCX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ResumeFileNames.attachmentHeader(download.downloadName()))
                .body(new FileSystemResource(download.path()));
    }
}
