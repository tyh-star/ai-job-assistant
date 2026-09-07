package aijob.controller;

import aijob.dto.FeedbackRequest;
import aijob.dto.FeedbackResponse;
import aijob.dto.FeedbackSummaryResponse;
import aijob.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * "网站评价" (site feedback) -- a single 1-5 star rating plus an optional plain-text
 * comment, at most one per account. Every {@code /me} route resolves the caller from
 * {@code Authentication#getName()} (the JWT subject) -- there is no userId parameter
 * anywhere here, so there is nothing to guess: this API can only ever act on whichever
 * account the caller's own JWT names. {@code /summary} is the one public route (see
 * SecurityConfig) and returns ONLY the average rating and reviewer count -- never a
 * comment, an email, or a name; see FeedbackSummaryResponse's javadoc.
 * <p>
 * A comment is always plain text: stored as-is, returned as-is, and the frontend
 * renders it as a plain text node -- never {@code dangerouslySetInnerHTML} -- which is
 * what actually prevents a submitted comment from ever executing as script in another
 * visitor's browser.
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping("/me")
    public ResponseEntity<FeedbackResponse> getMyFeedback(Authentication authentication) {
        return ResponseEntity.ok(feedbackService.getMyFeedback(authentication.getName()));
    }

    @PutMapping("/me")
    public ResponseEntity<FeedbackResponse> saveMyFeedback(
            Authentication authentication,
            @Valid @RequestBody FeedbackRequest request) {

        return ResponseEntity.ok(feedbackService.saveMyFeedback(authentication.getName(), request));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMyFeedback(Authentication authentication) {
        feedbackService.deleteMyFeedback(authentication.getName());
        return ResponseEntity.noContent().build();
    }

    /** Public -- see SecurityConfig's permitAll list. Never requires an account. */
    @GetMapping("/summary")
    public ResponseEntity<FeedbackSummaryResponse> getSummary() {
        return ResponseEntity.ok(feedbackService.getSummary());
    }
}
