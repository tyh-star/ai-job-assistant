package aijob.rewrite.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One structured-output chat call. Implementations return the parsed JSON object the
 * model produced for the given schema, or throw {@link RewriteAiException}. They never
 * retry on their own; callers decide (at most once, for invalid output only).
 */
public interface RewriteAiClient {

    JsonNode completeJson(RewriteAiRequest request);

    /** True when an API key is configured; the endpoints answer 503 otherwise. */
    boolean isConfigured();
}
