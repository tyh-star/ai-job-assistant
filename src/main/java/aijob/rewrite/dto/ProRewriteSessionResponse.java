package aijob.rewrite.dto;

import aijob.rewrite.ProRewriteState;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The current state of one Pro session. status: ANALYZED -> ANSWERED -> PREVIEWED ->
 * COMPLETED. downloadUrl is only set once the user confirmed and the file was written.
 */
public record ProRewriteSessionResponse(
        Long sessionId,
        String status,
        Long resumeId,
        Long jobId,
        String model,
        LocalDateTime expiresAt,
        ProRewriteState.Requirements requirements,
        List<ProRewriteState.Item> items,
        List<ProRewriteState.Question> questions,
        List<ProRewriteState.Suggestion> suggestions,
        int rejectedSuggestionCount,
        List<ProRewriteState.FinalChange> finalChanges,
        String downloadUrl,
        String downloadFileName
) {
}
