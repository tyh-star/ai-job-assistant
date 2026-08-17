package aijob.controller;

import aijob.dto.ResumeAnalysisResponse;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeFileDownload;
import aijob.dto.ResumeResponse;
import aijob.dto.ResumeStructure;
import aijob.service.ResumeService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

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
            Authentication authentication,
            @RequestParam(defaultValue = "false") boolean includeDerived) {

        List<ResumeResponse> resumes =
                resumeService.getMyResumes(
                        authentication.getName(),
                        includeDerived
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

    @DeleteMapping("/{id}/family")
    public ResponseEntity<Void> deleteResumeFamily(
            Authentication authentication,
            @PathVariable Long id) {

        resumeService.deleteResumeFamily(
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

    @GetMapping("/{id}/structure")
    public ResponseEntity<ResumeStructure> getResumeStructure(
            Authentication authentication,
            @PathVariable Long id) {

        ResumeStructure structure =
                resumeService.getResumeStructure(
                        authentication.getName(),
                        id
                );

        return ResponseEntity.ok(structure);
    }

    @PutMapping("/{id}/structure")
    public ResponseEntity<ResumeStructure> updateResumeStructure(
            Authentication authentication,
            @PathVariable Long id,
            @RequestBody ResumeStructure structure) {

        ResumeStructure updated =
                resumeService.updateResumeStructure(
                        authentication.getName(),
                        id,
                        structure
                );

        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/canonicalize")
    public ResponseEntity<ResumeResponse> canonicalizeResume(
            Authentication authentication,
            @PathVariable Long id) {

        ResumeResponse canonical =
                resumeService.canonicalizeResume(
                        authentication.getName(),
                        id
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(canonical);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadResume(
            Authentication authentication,
            @PathVariable Long id) {

        ResumeFileDownload download = resumeService.downloadResume(
                authentication.getName(),
                id
        );

        Resource resource;
        try {
            resource = new UrlResource(download.getPath().toUri());
        } catch (MalformedURLException ex) {
            throw new IllegalStateException("Failed to read resume file");
        }

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.getFileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(resumeMediaType(download.getFileType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    private static MediaType resumeMediaType(String fileType) {
        if (fileType != null && fileType.toLowerCase(Locale.ROOT).equals("pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        return MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );
    }
}
