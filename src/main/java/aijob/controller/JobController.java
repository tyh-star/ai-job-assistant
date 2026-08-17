package aijob.controller;

import aijob.dto.BulkDeleteJobsRequest;
import aijob.dto.BulkDeleteJobsResponse;
import aijob.dto.ConfirmTailorProposalRequest;
import aijob.dto.CreateJobRequest;
import aijob.dto.ImportGreenhouseJobsRequest;
import aijob.dto.ImportJobsResponse;
import aijob.dto.JobMatchResponse;
import aijob.dto.JobResponse;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.UpdateJobRequest;
import aijob.enums.TailorMode;
import aijob.service.JobService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> createJob(
            Authentication authentication,
            @Valid @RequestBody CreateJobRequest request) {

        JobResponse job =
                jobService.createJob(
                        authentication.getName(),
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(job);
    }

    @GetMapping
    public ResponseEntity<List<JobResponse>> getMyJobs(
            Authentication authentication) {

        List<JobResponse> jobs =
                jobService.getMyJobs(
                        authentication.getName()
                );

        return ResponseEntity.ok(jobs);
    }

    @PostMapping("/imports/greenhouse")
    public ResponseEntity<ImportJobsResponse> importGreenhouseJobs(
            Authentication authentication,
            @Valid @RequestBody ImportGreenhouseJobsRequest request) {

        ImportJobsResponse imported =
                jobService.importGreenhouseJobs(
                        authentication.getName(),
                        request.getBoardToken()
                );

        return ResponseEntity.ok(imported);
    }

    @DeleteMapping("/bulk")
    public ResponseEntity<BulkDeleteJobsResponse> bulkDeleteJobs(
            Authentication authentication,
            @Valid @RequestBody BulkDeleteJobsRequest request) {

        BulkDeleteJobsResponse deleted =
                jobService.bulkDeleteJobs(
                        authentication.getName(),
                        request.getJobIds()
                );

        return ResponseEntity.ok(deleted);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJob(
            Authentication authentication,
            @PathVariable Long id) {

        JobResponse job =
                jobService.getJob(
                        authentication.getName(),
                        id
                );

        return ResponseEntity.ok(job);
    }

    @PutMapping("/{id}")
    public ResponseEntity<JobResponse> updateJob(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateJobRequest request) {

        JobResponse job =
                jobService.updateJob(
                        authentication.getName(),
                        id,
                        request
                );

        return ResponseEntity.ok(job);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(
            Authentication authentication,
            @PathVariable Long id) {

        jobService.deleteJob(
                authentication.getName(),
                id
        );

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/match")
    public ResponseEntity<JobMatchResponse> matchJob(
            Authentication authentication,
            @PathVariable Long jobId,
            @RequestParam Long resumeId) {

        JobMatchResponse match =
                jobService.matchJob(
                        authentication.getName(),
                        jobId,
                        resumeId
                );

        return ResponseEntity.ok(match);
    }

    @PostMapping("/{jobId}/tailor")
    public ResponseEntity<TailoredResumeResponse> tailorResume(
            Authentication authentication,
            @PathVariable Long jobId,
            @RequestParam Long resumeId,
            @RequestParam(defaultValue = "CONSERVATIVE") TailorMode mode) {

        TailoredResumeResponse tailored =
                jobService.tailorResume(
                        authentication.getName(),
                        jobId,
                        resumeId,
                        mode
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(tailored);
    }

    @GetMapping("/{jobId}/tailor-proposals/{proposalId}")
    public ResponseEntity<TailoredResumeResponse> getTailorProposal(
            Authentication authentication,
            @PathVariable Long jobId,
            @PathVariable Long proposalId) {

        TailoredResumeResponse proposal =
                jobService.getTailorProposal(
                        authentication.getName(),
                        jobId,
                        proposalId
                );

        return ResponseEntity.ok(proposal);
    }

    @PostMapping("/{jobId}/tailor-proposals/{proposalId}/confirm")
    public ResponseEntity<TailoredResumeResponse> confirmTailorProposal(
            Authentication authentication,
            @PathVariable Long jobId,
            @PathVariable Long proposalId,
            @Valid @RequestBody ConfirmTailorProposalRequest request) {

        TailoredResumeResponse confirmed =
                jobService.confirmTailorProposal(
                        authentication.getName(),
                        jobId,
                        proposalId,
                        request.getSelectedNodeIds()
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(confirmed);
    }

    @GetMapping("/{jobId}/tailored/{fileName}")
    public ResponseEntity<Resource> downloadTailoredResume(
            Authentication authentication,
            @PathVariable Long jobId,
            @PathVariable String fileName) {

        Path file = jobService.downloadTailoredResume(
                authentication.getName(),
                jobId,
                fileName
        );

        Resource resource;
        try {
            resource = new UrlResource(file.toUri());
        } catch (MalformedURLException ex) {
            throw new IllegalStateException("Failed to read tailored resume");
        }

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.getFileName().toString(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(tailoredMediaType(fileName))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    private static MediaType tailoredMediaType(String fileName) {
        if (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        return MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );
    }
}
