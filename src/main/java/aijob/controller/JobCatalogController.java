package aijob.controller;

import aijob.dto.CitySuggestion;
import aijob.dto.JobCatalogDetail;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.SaveCatalogJobResponse;
import aijob.service.JobCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Search over the site's job catalog. {@code GET search} and {@code GET
 * /{catalogJobId}} are public -- browsing needs no account, see
 * {@code aijob.config.SecurityConfig}. Every other route ({@code save}, {@code applied})
 * still needs a JWT, exactly like the rest of {@code /api/v1} outside
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
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String cityCountry,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String remoteType,
            @RequestParam(required = false) String employmentType,
            @RequestParam(required = false) Integer postedWithinDays,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {

        JobCatalogSearchResponse response = jobCatalogService.search(
                emailOrNullForAnonymous(authentication),
                new JobCatalogSearchRequest(
                        q, location, city, cityCountry, country, remoteType, employmentType,
                        postedWithinDays, page, size, sort));
        return ResponseEntity.ok(response);
    }

    /**
     * Structured city suggestions for the search form's city facet. Public, like
     * {@code search} and {@code detail} -- a reference lookup, not user-scoped data.
     * {@code GET /api/v1/job-catalog/cities} is a literal route matched ahead of
     * {@code /{catalogJobId}} (Spring resolves the exact path before the variable one,
     * same as the existing {@code /search} route), so it can never be mistaken for a
     * detail lookup on a posting literally named "cities".
     */
    @GetMapping("/cities")
    public ResponseEntity<List<CitySuggestion>> cities(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String country) {
        return ResponseEntity.ok(jobCatalogService.searchCities(q, country));
    }

    @GetMapping("/{catalogJobId}")
    public ResponseEntity<JobCatalogDetail> detail(
            Authentication authentication,
            @PathVariable String catalogJobId) {

        return ResponseEntity.ok(
                jobCatalogService.findDetail(emailOrNullForAnonymous(authentication), catalogJobId));
    }

    @PostMapping("/{catalogJobId}/save")
    public ResponseEntity<SaveCatalogJobResponse> save(
            Authentication authentication,
            @PathVariable String catalogJobId) {

        SaveCatalogJobResponse response =
                jobCatalogService.saveToMyJobs(authentication.getName(), catalogJobId);
        return ResponseEntity
                .status(response.alreadySaved() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /**
     * Confirms the user actually submitted an application -- called only after the user
     * explicitly answers "yes" to a prompt on their own side; this backend never infers
     * it from the apply-link click alone. Requires a JWT (never reachable anonymously),
     * so {@code authentication.getName()} is always a real user's email here.
     */
    @PostMapping("/{catalogJobId}/applied")
    public ResponseEntity<SaveCatalogJobResponse> markApplied(
            Authentication authentication,
            @PathVariable String catalogJobId) {

        return ResponseEntity.ok(jobCatalogService.markApplied(authentication.getName(), catalogJobId));
    }

    /** Reverses a mistaken confirmation. Requires a JWT, same as {@link #markApplied}. */
    @DeleteMapping("/{catalogJobId}/applied")
    public ResponseEntity<SaveCatalogJobResponse> clearApplied(
            Authentication authentication,
            @PathVariable String catalogJobId) {

        return ResponseEntity.ok(jobCatalogService.clearApplied(authentication.getName(), catalogJobId));
    }

    /**
     * {@code search} and {@code detail} are public, so {@code authentication} here is
     * either a real JWT-backed principal or Spring Security's own
     * {@link AnonymousAuthenticationToken} (never {@code null} -- Spring always
     * populates SOME authentication for a permitAll route once anonymous auth, which is
     * on by default, is enabled). Only a real principal's email is ever passed down;
     * anonymous browsing must never resolve to "anonymousUser" being looked up as if it
     * were a real account.
     */
    private static String emailOrNullForAnonymous(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }
}
