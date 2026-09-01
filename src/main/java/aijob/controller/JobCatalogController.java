package aijob.controller;

import aijob.dto.JobCatalogDetail;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.SaveCatalogJobResponse;
import aijob.service.JobCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search over jobs this deployment has already collected from public ATS boards.
 * Every route needs a JWT, exactly like the rest of {@code /api/v1} outside
 * {@code /api/v1/auth/**}.
 */
@RestController
@RequestMapping("/api/v1/job-catalog")
public class JobCatalogController {

    private final JobCatalogService jobCatalogService;

    public JobCatalogController(JobCatalogService jobCatalogService) {
        this.jobCatalogService = jobCatalogService;
    }

    @GetMapping("/search")
    public ResponseEntity<JobCatalogSearchResponse> search(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String remoteType,
            @RequestParam(required = false) String employmentType,
            @RequestParam(required = false) Integer postedWithinDays,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {

        JobCatalogSearchResponse response = jobCatalogService.search(
                authentication.getName(),
                new JobCatalogSearchRequest(
                        q, location, country, remoteType, employmentType,
                        postedWithinDays, page, size, sort));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{catalogJobId}")
    public ResponseEntity<JobCatalogDetail> detail(
            Authentication authentication,
            @PathVariable Long catalogJobId) {

        return ResponseEntity.ok(
                jobCatalogService.findDetail(authentication.getName(), catalogJobId));
    }

    @PostMapping("/{catalogJobId}/save")
    public ResponseEntity<SaveCatalogJobResponse> save(
            Authentication authentication,
            @PathVariable Long catalogJobId) {

        SaveCatalogJobResponse response =
                jobCatalogService.saveToMyJobs(authentication.getName(), catalogJobId);
        return ResponseEntity
                .status(response.alreadySaved() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }
}
