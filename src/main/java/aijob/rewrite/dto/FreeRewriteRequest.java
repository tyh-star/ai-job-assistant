package aijob.rewrite.dto;

import jakarta.validation.constraints.NotNull;

/** Free Resume Rewrite needs a resume only -- never a job. */
public record FreeRewriteRequest(@NotNull Long resumeId) {
}
