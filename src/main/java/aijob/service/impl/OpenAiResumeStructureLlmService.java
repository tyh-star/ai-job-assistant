package aijob.service.impl;

import aijob.dto.PdfLayout;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructureLlmResponse;
import aijob.dto.ResumeTextBlock;
import aijob.exception.AiServiceException;
import aijob.service.ResumeStructureLlmService;
import aijob.util.ResumeStructureGrounder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponseCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiResumeStructureLlmService implements ResumeStructureLlmService {

    private static final Logger log =
            LoggerFactory.getLogger(OpenAiResumeStructureLlmService.class);

    static final String INSTRUCTIONS = """
            You classify layout blocks from one PDF resume into a structured resume.

            You are given numbered blocks. Each block has id, page, text, and features.
            Use ONLY these blocks. Do not use outside knowledge.

            Copy. Do not paraphrase, translate, expand, summarize in your own words,
            or fix grammar. Every value must be an exact substring of the cited block text.

            Always return every field in the schema. If a section does not exist, use
            empty strings, empty lists, sourcePage 0, and empty sourceBlockIds.

            Fill:
            - summaryBullets: copy EACH qualification/summary bullet as its own item.
              Do not join bullets into one paragraph. Strip a leading bullet glyph
              if present, but keep the original sentence.
            - skills: copy individual tools/languages/libraries from skills or
              technical-proficiency blocks. Split comma lists. Do not add tools
              that are not written in the blocks.
            - experience: paid jobs / internships only if those words appear in blocks
            - projects: copy name, organization or type (for example Personal Project
              or a school name), dates as written (keep original dashes), bullets,
              and links. A line like "Project Link: http://..." is a link, not a bullet.
            - education: copy school, degree, dates as written. Put a
              "Relevant Courses: ..." line into details. Cite every block you copy
              from, including a separate courses block on the same page.

            Do NOT fill contact, name, email, phone, or URLs. Those are handled elsewhere.

            Never invent employers, internships, degrees, dates, Docker, Kubernetes,
            or any skill/company/school that is not in a cited block.

            Every non-empty value MUST include:
            sourceBlockIds, sourcePage, sourceText, confidence.
            sourceText must be copied from the cited blocks.
            sourcePage must match those blocks.
            """;

    private static final ObjectMapper INPUT_MAPPER = new ObjectMapper();

    private final OpenAIClient openAIClient;
    private final String model;
    private final ResumeStructureGrounder grounder;

    public OpenAiResumeStructureLlmService(
            OpenAIClient openAIClient,
            @Value("${openai.model}") String model,
            ResumeStructureGrounder grounder) {
        this.openAIClient = openAIClient;
        this.model = model;
        this.grounder = grounder;
    }

    @Override
    public ResumeStructure classify(PdfLayout layout, ResumeStructure ruleDraft) {
        ResumeStructureLlmResponse raw = requestClassification(layout);
        return grounder.merge(layout, ruleDraft, raw);
    }

    private ResumeStructureLlmResponse requestClassification(PdfLayout layout) {
        String input = buildInput(layout);
        StructuredResponseCreateParams<ResumeStructureLlmResponse> params =
                ResponseCreateParams.builder()
                        .model(model)
                        .instructions(INSTRUCTIONS)
                        .input(input)
                        .text(ResumeStructureLlmResponse.class)
                        .build();

        try {
            return openAIClient.responses()
                    .create(params)
                    .output()
                    .stream()
                    .flatMap(item -> item.message().stream())
                    .flatMap(message -> message.content().stream())
                    .flatMap(content -> content.outputText().stream())
                    .findFirst()
                    .orElseThrow(() -> new AiServiceException(
                            "AI analysis is temporarily unavailable"
                    ));
        } catch (AiServiceException e) {
            throw e;
        } catch (OpenAIServiceException e) {
            logOpenAiFailure(e);
            throw new AiServiceException("AI analysis is temporarily unavailable", e);
        } catch (RuntimeException e) {
            log.error("OpenAI resume structure classification failed: {}", e.getClass().getName());
            throw new AiServiceException("AI analysis is temporarily unavailable", e);
        }
    }

    static void logOpenAiFailure(OpenAIServiceException error) {
        log.error(
                "OpenAI resume structure classification failed: status={} type={} param={} code={} requestId={} message={}",
                error.statusCode(),
                error.type().orElse("-"),
                error.param().orElse("-"),
                error.code().orElse("-"),
                requestId(error.headers()),
                safeOpenAiMessage(error)
        );
    }

    static String requestId(Headers headers) {
        if (headers == null) {
            return "-";
        }
        List<String> values = headers.values("x-request-id");
        if (values == null || values.isEmpty()) {
            return "-";
        }
        return values.get(0);
    }

    static String safeOpenAiMessage(OpenAIServiceException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = String.valueOf(error.body());
        }
        if (message == null || message.isBlank() || "null".equals(message)) {
            return "-";
        }
        String redacted = message
                .replaceAll("(?i)sk-[A-Za-z0-9\\-_]+", "[redacted]")
                .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[redacted]");
        return redacted.length() > 800 ? redacted.substring(0, 800) : redacted;
    }

    static String buildInput(PdfLayout layout) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (layout != null && layout.getBlocks() != null) {
            for (ResumeTextBlock block : layout.getBlocks()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", block.getId());
                row.put("page", block.getPage());
                row.put("text", block.getText());
                row.put("features", block.getFeatures() == null ? List.of() : block.getFeatures());
                blocks.add(row);
            }
        }
        try {
            return INPUT_MAPPER.writeValueAsString(Map.of("blocks", blocks));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize layout blocks for classification", e);
        }
    }
}
