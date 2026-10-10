package aijob.rewrite.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Resume-rewrite AI settings. The key only ever comes from the server environment
 * (GROQ_API_KEY); it is never logged or returned.
 */
@Component
public class RewriteAiProperties {

    private final String baseUrl;
    private final String apiKey;
    private final String freeModel;
    private final String proModel;
    private final Duration timeout;

    public RewriteAiProperties(
            @Value("${aijob.rewrite.base-url:https://api.groq.com/openai/v1}") String baseUrl,
            @Value("${aijob.rewrite.api-key:}") String apiKey,
            @Value("${aijob.rewrite.free-model:openai/gpt-oss-20b}") String freeModel,
            @Value("${aijob.rewrite.pro-model:openai/gpt-oss-120b}") String proModel,
            @Value("${aijob.rewrite.timeout-seconds:60}") long timeoutSeconds) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.freeModel = freeModel;
        this.proModel = proModel;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public String baseUrl() {
        return baseUrl;
    }

    String apiKey() {
        return apiKey;
    }

    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }

    public String freeModel() {
        return freeModel;
    }

    public String proModel() {
        return proModel;
    }

    public Duration timeout() {
        return timeout;
    }
}
