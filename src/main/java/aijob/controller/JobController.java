package aijob.controller;

import aijob.dto.BulkDeleteJobsRequest;
import aijob.dto.BulkDeleteJobsResponse;
import aijob.dto.ConfirmTailorProposalRequest;
import aijob.dto.CreateJobRequest;
import aijob.dto.ImportSearchResultsRequest;
import aijob.dto.ImportSearchResultsResponse;
import aijob.dto.ImportGreenhouseJobsRequest;
import aijob.dto.ImportJobsResponse;
import aijob.dto.JobMatchResponse;
import aijob.dto.JobResponse;
import aijob.dto.JobSearchRequest;
import aijob.dto.JobSearchResponse;
import aijob.dto.JobSearchNextPageRequest;
import aijob.dto.SaveJobSearchResultRequest;
import aijob.dto.SaveJobSearchResultResponse;
import aijob.dto.TailorAnalysisResponse;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.UpdateJobRequest;
import aijob.enums.TailorMode;
import aijob.service.JobService;
import aijob.service.TailorAnalysisService;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;
    private final TailorAnalysisService tailorAnalysisService;

    public JobController(JobService jobService, TailorAnalysisService tailorAnalysisService) {
        this.jobService = jobService;
        this.tailorAnalysisService = tailorAnalysisService;
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

    @GetMapping("/search")
    public ResponseEntity<JobSearchResponse> searchJobs(
            Authentication authentication,
            @RequestParam(required = false) String keywords,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) Integer postedWithinDays,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {

        JobSearchResponse results = jobService.searchJobs(
                authentication.getName(),
                new JobSearchRequest(keywords, location, postedWithinDays, page, pageSize)
        );
        return ResponseEntity.ok(results);
    }

    @PostMapping("/search-sessions/{searchSessionId}/next")
    public ResponseEntity<JobSearchResponse> loadNextSearchPage(
            Authentication authentication,
            @PathVariable UUID searchSessionId,
            @RequestBody JobSearchNextPageRequest request) {

        JobSearchResponse results = jobService.loadNextSearchPage(
                authentication.getName(),
                searchSessionId,
                request == null ? 0 : request.getExpectedPage()
        );
        return ResponseEntity.ok(results);
    }

    @PostMapping("/search-results/import")
    public ResponseEntity<ImportSearchResultsResponse> importSearchResults(
            Authentication authentication,
            @Valid @RequestBody ImportSearchResultsRequest request) {

        ImportSearchResultsResponse imported =
                jobService.importSearchResults(authentication.getName(), request);
        return ResponseEntity.ok(imported);
    }

    @PostMapping("/search-results/save")
    public ResponseEntity<SaveJobSearchResultResponse> saveSearchResult(
            Authentication authentication,
            @Valid @RequestBody SaveJobSearchResultRequest request) {

        SaveJobSearchResultResponse saved =
                jobService.saveSearchResult(authentication.getName(), request);
        return ResponseEntity
                .status(saved.isCreated() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(saved);
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

    @PostMapping("/{jobId}/tailor-analysis")
    public ResponseEntity<TailorAnalysisResponse> analyzeTailorFit(
            Authentication authentication,
            @PathVariable Long jobId,
            @RequestParam Long resumeId) {

        TailorAnalysisResponse analysis =
                tailorAnalysisService.analyze(
                        authentication.getName(),
                        jobId,
                        resumeId
                );

        return ResponseEntity.ok(analysis);
    }

    @PostMapping("/{jobId}/tailor-analysis/profile/refresh")
    public ResponseEntity<TailorAnalysisResponse> refreshTailorAnalysisProfile(
            Authentication authentication,
            @PathVariable Long jobId,
            @RequestParam Long resumeId) {

        TailorAnalysisResponse analysis =
                tailorAnalysisService.refreshProfile(
                        authentication.getName(),
                        jobId,
                        resumeId
                );

        return ResponseEntity.ok(analysis);
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

    @PostMapping("/{jobId}/tailor-proposals")
    public ResponseEntity<TailoredResumeResponse> createTailorProposal(
            Authentication authentication,
            @PathVariable Long jobId,
            @RequestParam Long resumeId,
            @RequestParam(required = false) String productMode) {

        TailoredResumeResponse proposal =
                jobService.createTailorProposal(
                        authentication.getName(),
                        jobId,
                        resumeId,
                        productMode
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(proposal);
    }

    @PostMapping("/{jobId}/tailor-enhanced/opportunity")
    public ResponseEntity<aijob.tailor.v3.V3OpportunityMap> scanEnhancedOpportunity(
            Authentication authentication,
            @PathVariable Long jobId,
            @RequestParam Long resumeId,
            @RequestBody(required = false) aijob.dto.ScanEnhancedOpportunityRequest request) {

        java.util.List<String> skipped = request == null ? java.util.List.of() : request.getSkippedQuestionIds();
        return ResponseEntity.ok(jobService.scanEnhancedOpportunity(
                authentication.getName(), jobId, resumeId, skipped));
    }

    @PostMapping("/{jobId}/tailor-enhanced/answers")
    public ResponseEntity<aijob.dto.AnswerEnhancedQuestionResponse> answerEnhancedQuestion(
            Authentication authentication,
            @PathVariable Long jobId,
            @RequestParam Long resumeId,
            @RequestBody aijob.dto.AnswerEnhancedQuestionRequest request) {

        aijob.dto.AnswerEnhancedQuestionResponse answered = jobService.answerEnhancedQuestion(
                authentication.getName(), jobId, resumeId, request);
        return ResponseEntity.status(answered.isSkipped() ? HttpStatus.OK : HttpStatus.CREATED).body(answered);
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
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(confirmed);
    }

    @PostMapping("/{jobId}/tailor-proposals/{proposalId}/continue")
    public ResponseEntity<TailoredResumeResponse> continueTailorProposal(
            Authentication authentication,
            @PathVariable Long jobId,
            @PathVariable Long proposalId) {

        TailoredResumeResponse continued =
                jobService.continueTailorProposal(
                        authentication.getName(),
                        jobId,
                        proposalId
                );

        return ResponseEntity.ok(continued);
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
