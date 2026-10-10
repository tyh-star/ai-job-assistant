package aijob.rewrite.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroqRewriteAiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GroqRewriteAiClient client(int status, String body, long delayMs, long timeoutSeconds) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1";
        RewriteAiProperties properties = new RewriteAiProperties(base, "test-key-not-real",
                "openai/gpt-oss-20b", "openai/gpt-oss-120b", timeoutSeconds);
        return new GroqRewriteAiClient(properties, mapper);
    }

    private RewriteAiRequest request(String model) {
        return new RewriteAiRequest(model, "system", "user", "free_resume_rewrite",
                mapper.createObjectNode().put("type", "object"), 0.2, "low", 1000);
    }

    private static String completion(String content) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":" + quote(content) + "}}]}";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void sendsTheChosenModelWithAStrictJsonSchemaAndParsesTheObject() throws Exception {
        GroqRewriteAiClient client = client(200, completion("{\"changes\":[]}"), 0, 10);
        JsonNode result = client.completeJson(request("openai/gpt-oss-20b"));
        assertTrue(result.get("changes").isArray());
        JsonNode sent = mapper.readTree(lastBody.get());
        assertEquals("openai/gpt-oss-20b", sent.get("model").asText());
        assertEquals("json_schema", sent.at("/response_format/type").asText());
        assertTrue(sent.at("/response_format/json_schema/strict").asBoolean());
        assertEquals(0.2, sent.get("temperature").asDouble());
        assertEquals("Bearer test-key-not-real", lastAuth.get());

        client.completeJson(request("openai/gpt-oss-120b"));
        assertEquals("openai/gpt-oss-120b", mapper.readTree(lastBody.get()).get("model").asText());
    }

    @Test
    void mapsRateLimitTimeoutAndUpstreamErrors() throws Exception {
        assertEquals(RewriteAiException.RATE_LIMITED,
                assertThrows(RewriteAiException.class, () -> client(429, "{}", 0, 10).completeJson(request("m"))).code());
        stop();
        assertEquals(RewriteAiException.UPSTREAM_ERROR,
                assertThrows(RewriteAiException.class, () -> client(503, "{}", 0, 10).completeJson(request("m"))).code());
        stop();
        RewriteAiException timeout = assertThrows(RewriteAiException.class,
                () -> client(200, completion("{}"), 7000, 5).completeJson(request("m")));
        assertEquals(RewriteAiException.TIMEOUT, timeout.code());
    }

    @Test
    void invalidModelOutputIsReportedAsSuch() throws Exception {
        assertEquals(RewriteAiException.INVALID_OUTPUT, assertThrows(RewriteAiException.class,
                () -> client(200, completion("not json at all"), 0, 10).completeJson(request("m"))).code());
        stop();
        assertEquals(RewriteAiException.INVALID_OUTPUT, assertThrows(RewriteAiException.class,
                () -> client(400, "{\"error\":{\"code\":\"json_validate_failed\"}}", 0, 10).completeJson(request("m"))).code());
    }

    @Test
    void withoutAKeyItRefusesAndNeverCallsOut() {
        RewriteAiProperties properties = new RewriteAiProperties("http://127.0.0.1:1", "",
                "openai/gpt-oss-20b", "openai/gpt-oss-120b", 5);
        GroqRewriteAiClient client = new GroqRewriteAiClient(properties, mapper);
        assertFalse(client.isConfigured());
        assertEquals(RewriteAiException.NOT_CONFIGURED,
                assertThrows(RewriteAiException.class, () -> client.completeJson(request("m"))).code());
    }
}
