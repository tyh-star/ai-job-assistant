package aijob.rewrite.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI-compatible chat completions against Groq (AI_BASE_URL), with a strict
 * json_schema response format. Plain JDK HttpClient: an explicit timeout, no hidden
 * SDK retries, and a clear mapping of 429 / timeouts / 5xx / unusable output.
 * Logs only the model, status and timing -- never the key, prompt or resume text.
 */
@Component
public class GroqRewriteAiClient implements RewriteAiClient {

    private static final Logger log = LoggerFactory.getLogger(GroqRewriteAiClient.class);

    private final RewriteAiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public GroqRewriteAiClient(RewriteAiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build());
    }

    GroqRewriteAiClient(RewriteAiProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isConfigured() {
        return properties.hasApiKey();
    }

    @Override
    public JsonNode completeJson(RewriteAiRequest request) {
        if (!properties.hasApiKey()) {
            throw RewriteAiException.notConfigured();
        }
        String body = requestBody(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/chat/completions"))
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            log.warn("rewrite.ai timeout model={}", request.model());
            throw RewriteAiException.timeout();
        } catch (IOException e) {
            log.warn("rewrite.ai io-error model={} type={}", request.model(), e.getClass().getSimpleName());
            throw RewriteAiException.upstream(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RewriteAiException.timeout();
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        int status = response.statusCode();
        log.info("rewrite.ai model={} status={} latencyMs={}", request.model(), status, elapsedMs);

        if (status == 429) {
            throw RewriteAiException.rateLimited();
        }
        if (status == 408 || status == 504) {
            throw RewriteAiException.timeout();
        }
        if (status < 200 || status >= 300) {
            // Groq answers 400 json_validate_failed when strict output generation fails.
            if (status == 400 && response.body() != null && response.body().contains("json_validate_failed")) {
                throw RewriteAiException.invalidOutput("the model did not produce valid JSON for the schema");
            }
            throw RewriteAiException.upstream(status);
        }
        return parseContent(response.body());
    }

    String requestBody(RewriteAiRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.model());
        root.put("temperature", request.temperature());
        root.put("max_completion_tokens", request.maxOutputTokens());
        if (request.reasoningEffort() != null) {
            root.put("reasoning_effort", request.reasoningEffort());
        }
        var messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        messages.addObject().put("role", "user").put("content", request.userPrompt());
        ObjectNode format = root.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", request.schemaName());
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", request.schema());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build AI request", e);
        }
    }

    private JsonNode parseContent(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw RewriteAiException.invalidOutput("the provider response was not JSON");
        }
        JsonNode choice = root.path("choices").path(0);
        String finishReason = choice.path("finish_reason").asText("");
        String content = choice.path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw RewriteAiException.invalidOutput("empty model output (finish_reason=" + finishReason + ")");
        }
        try {
            JsonNode parsed = objectMapper.readTree(content);
            if (parsed == null || !parsed.isObject()) {
                throw RewriteAiException.invalidOutput("the model output is not a JSON object");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw RewriteAiException.invalidOutput("the model output is not valid JSON");
        }
    }
}
