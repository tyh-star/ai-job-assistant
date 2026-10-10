package aijob.rewrite;

import aijob.rewrite.ai.RewriteAiClient;
import aijob.rewrite.ai.RewriteAiException;
import aijob.rewrite.ai.RewriteAiRequest;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Runs one structured AI step with at most one automatic retry. The retry happens only
 * when the output is unusable (invalid JSON, wrong shape) or breaks a rule; rate limits,
 * timeouts and provider errors fail at once. After the retry, items that still break a
 * rule are dropped -- they never reach the resume.
 */
final class RewriteAttempts {

    static final int MAX_ATTEMPTS = 2;

    private RewriteAttempts() {
    }

    record Checked<T>(T item, List<String> violations) {
    }

    record Outcome<T>(List<T> accepted, List<Checked<T>> rejected, int attempts) {
    }

    interface Step<T> {
        /** Parses the model JSON; throws {@link RewriteAiException} INVALID_OUTPUT when the shape is wrong. */
        List<T> parse(JsonNode json);

        /** Rule violations for one parsed item; empty when it may be used. */
        List<String> check(T item);

        /** A short label for feedback, e.g. the node id. */
        String label(T item);
    }

    static <T> Outcome<T> run(RewriteAiClient client, Function<String, RewriteAiRequest> requestForFeedback, Step<T> step) {
        String feedback = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            List<T> parsed;
            try {
                parsed = step.parse(client.completeJson(requestForFeedback.apply(feedback)));
            } catch (RewriteAiException e) {
                if (RewriteAiException.INVALID_OUTPUT.equals(e.code()) && attempt < MAX_ATTEMPTS) {
                    feedback = "Your previous answer was not valid JSON for the required schema. Return only the JSON object.";
                    continue;
                }
                throw e;
            }
            List<T> accepted = new ArrayList<>();
            List<Checked<T>> rejected = new ArrayList<>();
            for (T item : parsed) {
                List<String> violations = step.check(item);
                if (violations.isEmpty()) {
                    accepted.add(item);
                } else {
                    rejected.add(new Checked<>(item, violations));
                }
            }
            if (rejected.isEmpty() || attempt == MAX_ATTEMPTS) {
                return new Outcome<>(accepted, rejected, attempt);
            }
            StringBuilder note = new StringBuilder(
                    "Some of your previous changes broke the rules and were rejected. Fix or omit them:\n");
            for (Checked<T> bad : rejected) {
                note.append("- ").append(step.label(bad.item())).append(": ")
                        .append(String.join("; ", bad.violations())).append('\n');
            }
            feedback = note.toString();
        }
        throw new IllegalStateException("unreachable");
    }
}
