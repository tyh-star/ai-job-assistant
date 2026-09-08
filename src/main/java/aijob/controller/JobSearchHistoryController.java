package aijob.controller;

import aijob.dto.JobSearchHistoryItemResponse;
import aijob.dto.RecordJobSearchHistoryRequest;
import aijob.service.JobSearchHistoryService;
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

/**
 * Persisted catalog-search history for the signed-in account. Anonymous visitors keep
 * history in the browser only -- this API is authenticated and never merges the two.
 * Cross-user ids are 404, same as a missing row.
 */
@RestController
@RequestMapping("/api/v1/job-search-history")
public class JobSearchHistoryController {

    private final JobSearchHistoryService jobSearchHistoryService;

    public JobSearchHistoryController(JobSearchHistoryService jobSearchHistoryService) {
        this.jobSearchHistoryService = jobSearchHistoryService;
    }

    @GetMapping
    public ResponseEntity<List<JobSearchHistoryItemResponse>> list(Authentication authentication) {
        return ResponseEntity.ok(jobSearchHistoryService.list(authentication.getName()));
    }

    @PostMapping
    public ResponseEntity<Void> record(
            Authentication authentication,
            @RequestBody(required = false) RecordJobSearchHistoryRequest request) {
        jobSearchHistoryService.record(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOne(Authentication authentication, @PathVariable Long id) {
        jobSearchHistoryService.deleteOne(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll(Authentication authentication) {
        jobSearchHistoryService.deleteAll(authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
