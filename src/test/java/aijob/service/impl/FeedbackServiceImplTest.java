package aijob.service.impl;

import aijob.dto.FeedbackRequest;
import aijob.dto.FeedbackResponse;
import aijob.entity.SiteFeedback;
import aijob.entity.User;
import aijob.mapper.FeedbackMapper;
import aijob.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackServiceImplTest {

    private static final String EMAIL = "alice@example.com";

    private FeedbackMapper feedbackMapper;
    private UserMapper userMapper;
    private FeedbackServiceImpl service;

    @BeforeEach
    void setUp() {
        feedbackMapper = mock(FeedbackMapper.class);
        userMapper = mock(UserMapper.class);
        service = new FeedbackServiceImpl(feedbackMapper, userMapper);

        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
    }

    private static SiteFeedback stored(int rating, String comment) {
        SiteFeedback feedback = new SiteFeedback();
        feedback.setId(9L);
        feedback.setUserId(1L);
        feedback.setRating(rating);
        feedback.setComment(comment);
        feedback.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        feedback.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return feedback;
    }

    @Test
    void aConcurrentFirstTimeInsertRaceFallsBackToUpdatingTheRowTheWinnerJustCreated() {
        // Simulates two tabs saving for the first time at once: this call sees no
        // existing row, attempts INSERT, and loses the unique-constraint race to the
        // other request -- it must recover by updating rather than surfacing a raw
        // database error as a 500.
        when(feedbackMapper.findByUserId(1L))
                .thenReturn(null)                          // pre-check: none yet
                .thenReturn(stored(5, "after fallback"));   // re-read after the recovery
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate"))
                .when(feedbackMapper).insert(any());

        FeedbackRequest request = new FeedbackRequest();
        request.setRating(5);
        request.setComment("after fallback");

        FeedbackResponse response = service.saveMyFeedback(EMAIL, request);

        verify(feedbackMapper).updateByUserId(eq(1L), eq(5), eq("after fallback"));
        assertEquals(5, response.getRating());
    }

    @Test
    void normalRepeatSavesGoStraightToUpdateWithoutEverAttemptingInsert() {
        when(feedbackMapper.findByUserId(1L)).thenReturn(stored(3, "existing"), stored(4, "changed"));

        FeedbackRequest request = new FeedbackRequest();
        request.setRating(4);
        request.setComment("changed");

        service.saveMyFeedback(EMAIL, request);

        verify(feedbackMapper, never()).insert(any());
        verify(feedbackMapper).updateByUserId(eq(1L), eq(4), eq("changed"));
    }

    @Test
    void aFirstTimeSaveWithNoRaceInsertsRatherThanUpdates() {
        when(feedbackMapper.findByUserId(1L)).thenReturn(null, stored(2, "first"));

        FeedbackRequest request = new FeedbackRequest();
        request.setRating(2);
        request.setComment("first");

        service.saveMyFeedback(EMAIL, request);

        verify(feedbackMapper).insert(any());
        verify(feedbackMapper, never()).updateByUserId(any(), anyInt(), anyString());
    }
}
