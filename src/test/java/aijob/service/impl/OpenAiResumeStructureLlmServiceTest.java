package aijob.service.impl;

import aijob.dto.PdfLayout;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructureLlmResponse;
import aijob.dto.ResumeTextBlock;
import aijob.exception.AiServiceException;
import aijob.util.ResumeStructureDraftBuilder;
import aijob.util.ResumeStructureGrounder;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.models.ErrorObject;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.services.blocking.ResponseService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiResumeStructureLlmServiceTest {

    private static final String SECRET_BLOCK =
            "CLASSIFIED_BLOCK_SECRET_TEXT_xyz never-log-this";
    private static final String SECRET_EMAIL = "secret.person@example.com";

    @Test
    void buildInput_sendsNumberedBlocksNotRawExtractedText() {
        ResumeTextBlock name = new ResumeTextBlock();
        name.setId("b-p1-0");
        name.setPage(1);
        name.setText("Jane Doe");
        name.setFeatures(List.of(ResumeTextBlock.FEATURE_LARGE_FONT));
        name.setX(12f);
        name.setY(40f);
        name.setFontSize(22f);

        PdfLayout layout = PdfLayout.ofBlocks(612, 792, List.of(name));
        String input = OpenAiResumeStructureLlmService.buildInput(layout);

        assertTrue(input.contains("\"id\":\"b-p1-0\""));
        assertTrue(input.contains("\"page\":1"));
        assertTrue(input.contains("\"text\":\"Jane Doe\""));
        assertTrue(input.contains("LARGE_FONT"));
        assertFalse(input.contains("\"x\""));
        assertFalse(input.contains("\"y\""));
        assertFalse(input.contains("\"fontSize\""));
        assertFalse(input.contains("extracted_text"));
        assertFalse(input.contains("extractedText"));
    }

    @Test
    void structuredOutputSchema_refNodesHaveNoSiblingKeywords() {
        StructuredResponseCreateParams<ResumeStructureLlmResponse> params =
                ResponseCreateParams.builder()
                        .model("gpt-4o")
                        .instructions("classify")
                        .input("{\"blocks\":[]}")
                        .text(ResumeStructureLlmResponse.class)
                        .build();

        JsonNode schema = params.rawParams()
                .text()
                .orElseThrow()
                .format()
                .orElseThrow()
                .asJsonSchema()
                ._schema()
                .asUnknown()
                .orElseThrow()
                .convert(JsonNode.class);

        assertNotNull(schema);
        String schemaJson = schema.toString();
        assertTrue(schemaJson.contains("summaryBullets"), schemaJson);
        assertTrue(schemaJson.contains("organizationOrType"), schemaJson);
        assertFalse(schemaJson.contains("\"summary\""), schemaJson);
        assertRefNodesHaveNoSiblingKeywords(schema, "$");
    }

    private static void assertRefNodesHaveNoSiblingKeywords(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            if (node.has("$ref")) {
                List<String> extras = new ArrayList<>();
                node.fieldNames().forEachRemaining(name -> {
                    if (!"$ref".equals(name)) {
                        extras.add(name);
                    }
                });
                assertTrue(
                        extras.isEmpty(),
                        path + " has $ref plus extra keywords " + extras + ": " + node
                );
            }
            node.fields().forEachRemaining(entry ->
                    assertRefNodesHaveNoSiblingKeywords(entry.getValue(), path + "." + entry.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertRefNodesHaveNoSiblingKeywords(node.get(i), path + "[" + i + "]");
            }
        }
    }

    @Test
    void classify_badRequest_throwsWithoutLoggingBlockText() {
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        when(client.responses()).thenReturn(responses);
        BadRequestException error = badRequest(
                "Invalid schema for response_format 'json-schema-from-ResumeStructureLlmResponse': "
                        + "additionalProperties must be false"
        );
        when(responses.create(any(StructuredResponseCreateParams.class))).thenThrow(error);
        when(responses.create(any(ResponseCreateParams.class))).thenThrow(error);

        OpenAiResumeStructureLlmService service = new OpenAiResumeStructureLlmService(
                client,
                "gpt-4o",
                new ResumeStructureGrounder()
        );

        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResumeStructureLlmService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            PdfLayout layout = secretLayout();
            ResumeStructure draft = new ResumeStructureDraftBuilder().build(layout);
            assertThrows(AiServiceException.class, () -> service.classify(layout, draft));

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(logs.contains("status=400"), logs);
            assertTrue(logs.contains("req_test_123"), logs);
            assertTrue(logs.contains("additionalProperties must be false"), logs);
            assertFalse(logs.contains(SECRET_BLOCK), logs);
            assertFalse(logs.contains(SECRET_EMAIL), logs);
            assertFalse(logs.contains("sk-"), logs);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static BadRequestException badRequest(String message) {
        return BadRequestException.builder()
                .headers(Headers.builder().put("x-request-id", "req_test_123").build())
                .error(ErrorObject.builder()
                        .message(message)
                        .type("invalid_request_error")
                        .param("text.format")
                        .code("invalid_schema")
                        .build())
                .build();
    }

    private static PdfLayout secretLayout() {
        ResumeTextBlock secret = new ResumeTextBlock();
        secret.setId("b-p1-0");
        secret.setPage(1);
        secret.setText(SECRET_BLOCK + " " + SECRET_EMAIL);
        secret.setFeatures(List.of(ResumeTextBlock.FEATURE_EMAIL));
        secret.normalize();
        return PdfLayout.ofBlocks(612, 792, List.of(secret));
    }
}
