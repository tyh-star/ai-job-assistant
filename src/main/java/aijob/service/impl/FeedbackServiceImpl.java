package aijob.service.impl;

import aijob.dto.FeedbackRequest;
import aijob.dto.FeedbackResponse;
import aijob.dto.FeedbackSummaryResponse;
import aijob.entity.SiteFeedback;
import aijob.entity.User;
import aijob.exception.FeedbackNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.FeedbackMapper;
import aijob.mapper.UserMapper;
import aijob.service.FeedbackService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class FeedbackServiceImpl implements FeedbackService {

    private final FeedbackMapper feedbackMapper;
    private final UserMapper userMapper;

    public FeedbackServiceImpl(FeedbackMapper feedbackMapper, UserMapper userMapper) {
        this.feedbackMapper = feedbackMapper;
        this.userMapper = userMapper;
    }

    @Override
    public FeedbackResponse getMyFeedback(String email) {
        User user = getUserByEmail(email);
        SiteFeedback feedback = feedbackMapper.findByUserId(user.getId());
        if (feedback == null) {
            throw new FeedbackNotFoundException("No feedback submitted yet");
        }
        return toResponse(feedback);
    }

    @Override
    public FeedbackResponse saveMyFeedback(String email, FeedbackRequest request) {
        User user = getUserByEmail(email);
        String comment = normalizeComment(request.getComment());
        int rating = request.getRating();

        SiteFeedback existing = feedbackMapper.findByUserId(user.getId());
        if (existing == null) {
            SiteFeedback feedback = new SiteFeedback();
            feedback.setUserId(user.getId());
            feedback.setRating(rating);
            feedback.setComment(comment);
            try {
                feedbackMapper.insert(feedback);
            } catch (DuplicateKeyException e) {
                // Two concurrent first-time submissions from the same account (two
                // tabs) -- the second one loses the INSERT race to the unique
                // constraint on user_id, so it falls back to updating the row the
                // first one just created, rather than surfacing a raw DB error for
                // what is, from the user's point of view, just "save my rating".
                feedbackMapper.updateByUserId(user.getId(), rating, comment);
            }
        } else {
            feedbackMapper.updateByUserId(user.getId(), rating, comment);
        }

        return toResponse(feedbackMapper.findByUserId(user.getId()));
    }

    @Override
    public void deleteMyFeedback(String email) {
        User user = getUserByEmail(email);
        int deleted = feedbackMapper.deleteByUserId(user.getId());
        if (deleted == 0) {
            throw new FeedbackNotFoundException("No feedback to delete");
        }
    }

    @Override
    public FeedbackSummaryResponse getSummary() {
        return feedbackMapper.getSummary();
    }

    /** Blank after trimming is stored as NULL, matching "comment is optional". */
    private static String normalizeComment(String rawComment) {
        if (rawComment == null) {
            return null;
        }
        String trimmed = rawComment.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private User getUserByEmail(String email) {
        User user = userMapper.findByEmail(email);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        return user;
    }

    private static FeedbackResponse toResponse(SiteFeedback feedback) {
        return new FeedbackResponse(
                feedback.getRating(),
                feedback.getComment(),
                feedback.getCreatedAt(),
                feedback.getUpdatedAt());
    }
}
