package aijob.controller;

import aijob.dto.ConfirmTailorProposalRequest;
import aijob.dto.CreateJobRequest;
import aijob.dto.JobMatchResponse;
import aijob.dto.JobResponse;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.UpdateJobRequest;
import aijob.enums.TailorMode;
import aijob.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}
