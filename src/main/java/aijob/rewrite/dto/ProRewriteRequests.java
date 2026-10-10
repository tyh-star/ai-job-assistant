package aijob.rewrite.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request bodies for the Pro Resume Rewrite session steps. */
public final class ProRewriteRequests {

    private ProRewriteRequests() {
    }

    /** Step 1: Pro always needs both a resume and a target job. */
    public record Start(@NotNull Long resumeId, @NotNull Long jobId) {
    }

    /** Step 3: at most five answers; hasExperience=false means "no / skip". */
    public record Answers(@NotNull @Size(max = 5) @Valid List<Answer> answers) {
    }

    public record Answer(@NotNull String questionId, boolean hasExperience, @Size(max = 1000) String detail) {
    }

    /** Step 5: the suggestions the user accepts; nothing is written without this. */
    public record Confirm(@NotEmpty @Size(max = 100) List<String> acceptedSuggestionIds) {
    }
}
