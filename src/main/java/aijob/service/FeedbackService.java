package aijob.service;

import aijob.dto.FeedbackRequest;
import aijob.dto.FeedbackResponse;
import aijob.dto.FeedbackSummaryResponse;

public interface FeedbackService {

    /** @throws aijob.exception.FeedbackNotFoundException if the caller has never submitted feedback. */
    FeedbackResponse getMyFeedback(String email);

    /** Creates the caller's feedback if none exists yet, otherwise updates it in place. */
    FeedbackResponse saveMyFeedback(String email, FeedbackRequest request);

    /** @throws aijob.exception.FeedbackNotFoundException if the caller has no feedback to delete. */
    void deleteMyFeedback(String email);

    /** Public: average rating and reviewer count only -- see FeedbackMapper#getSummary. */
    FeedbackSummaryResponse getSummary();
}
