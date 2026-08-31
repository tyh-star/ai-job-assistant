package aijob.tailor.v3;

import aijob.exception.AiServiceException;
import com.openai.client.OpenAIClient;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class OpenAiV3EnhancedEntryRewriteGenerator implements V3EnhancedEntryRewriteGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenAiV3EnhancedEntryRewriteGenerator.class);

    private static final String INSTRUCTIONS = """
            You rewrite one resume PROJECT or EXPERIENCE entry as a coordinated whole.
            Return TWO complete entry versions in one response. This is one model call.
            Do not rewrite bullets independently. Do not generate three candidates per sentence.

            Variant CLARITY_FIRST:
            - Prioritize grammar, tense, concision, and natural wording.
            - Do not force JD keywords into the bullets.

            Variant JD_TARGETED:
            - Use the same allowed facts plus USER CONFIRMED facts for this entry.
            - Shift emphasis toward the ranked JD requirements.
            - Do not add skills or results that are not in this entry's resume facts or
              USER CONFIRMED facts.
            - Do not write USER REJECTED facts.
            - Do not use facts confirmed for a different entry.

            ENHANCED mode: resume facts plus exact-entry USER CONFIRMED facts.
            If there is no clear improvement, both variants may use decision=NO_CHANGE.
            Do not invent skills, employers, schools, metrics, or leadership.
            Do not copy facts from other entries.
            Do not change the title or dates.
            Each variant must return the same number of rewrittenNodes as writableNodes,
            same nodeIds, same order.
            Do not return internal fact keys of any kind.

            """ + V3LanguageGuide.TEXT;

    private final OpenAIClient openAIClient;
    private final String model;

    public OpenAiV3EnhancedEntryRewriteGenerator(
            OpenAIClient openAIClient,
            @Value("${openai.model}") String model) {
        this.openAIClient = openAIClient;
        this.model = model;
    }

    @Override
    public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
        if (request == null || request.canonicalEntryKey().isBlank()) {
            return V3EntryRewriteResult.noChange("");
        }
        long started = System.nanoTime();
        try {
            Parsed parsed = requestStructured(V3EnhancedEntryRewritePrompt.build(request));
            long latency = (System.nanoTime() - started) / 1_000_000L;
            V3GenerationTelemetry telemetry = V3GenerationTelemetry.of(
                    V3GenerationTelemetry.ENHANCED_PROMPT_VERSION,
                    model,
                    latency,
                    parsed.promptTokens,
                    parsed.completionTokens);
            log.info(
                    "v3.enhanced.generate key={} repair={} prompt={} model={} latencyMs={} promptTokens={} completionTokens={}",
                    request.canonicalEntryKey(),
                    request.repair(),
                    telemetry.promptVersion(),
                    telemetry.model(),
                    telemetry.latencyMs(),
                    telemetry.promptTokens() == null ? "unknown" : telemetry.promptTokens(),
                    telemetry.completionTokens() == null ? "unknown" : telemetry.completionTokens());
            return toResult(request, parsed.response, telemetry);
        } catch (RuntimeException ex) {
            log.warn("V3 entry generate failed: {}", ex.getClass().getSimpleName());
            return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
        }
    }

    private Parsed requestStructured(String input) {
        StructuredResponseCreateParams<ModelResponse> params =
                ResponseCreateParams.builder()
                        .model(model)
                        .instructions(INSTRUCTIONS)
                        .input(input)
                        .text(ModelResponse.class)
                        .build();
        StructuredResponse<ModelResponse> created = openAIClient.responses().create(params);
        ModelResponse response = created.output()
                .stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .findFirst()
                .orElseThrow(() -> new AiServiceException("AI analysis is temporarily unavailable"));
        return new Parsed(response, promptTokens(created.usage()), completionTokens(created.usage()));
    }

    static Integer promptTokens(Optional<ResponseUsage> usage) {
        if (usage == null || usage.isEmpty()) {
            return null;
        }
        return Math.toIntExact(usage.get().inputTokens());
    }

    static Integer completionTokens(Optional<ResponseUsage> usage) {
        if (usage == null || usage.isEmpty()) {
            return null;
        }
        return Math.toIntExact(usage.get().outputTokens());
    }

    private static V3EntryRewriteResult toResult(
            V3EntryRewriteRequest request,
            ModelResponse response,
            V3GenerationTelemetry telemetry) {
        V3EntryRewriteVariant clarity = toVariant(
                request, V3VariantType.CLARITY_FIRST, response == null ? null : response.clarityFirst);
        V3EntryRewriteVariant targeted = toVariant(
                request, V3VariantType.JD_TARGETED, response == null ? null : response.jdTargeted);
        return new V3EntryRewriteResult(request.canonicalEntryKey(), clarity, targeted, telemetry);
    }

    private static V3EntryRewriteVariant toVariant(
            V3EntryRewriteRequest request,
            V3VariantType type,
            ModelVariant raw) {
        if (raw == null || "NO_CHANGE".equalsIgnoreCase(raw.decision)) {
            return V3EntryRewriteVariant.noChange(type);
        }
        Set<String> allowedNodes = allowedNodeIds(request);
        List<V3RewrittenNode> nodes = new ArrayList<>();
        if (raw.rewrittenNodes != null) {
            for (ModelNode node : raw.rewrittenNodes) {
                if (node == null) {
                    continue;
                }
                List<String> cited = V3FactCoverageResolver.validCitations(node.citedSourceNodeIds, allowedNodes);
                List<String> ignored = V3FactCoverageResolver.invalidCitations(node.citedSourceNodeIds, allowedNodes);
                if (!ignored.isEmpty()) {
                    log.warn(
                            "v3.citation.ignored key={} node={} ignored={}",
                            request.canonicalEntryKey(),
                            node.nodeId,
                            ignored);
                }
                nodes.add(new V3RewrittenNode(
                        node.nodeId,
                        node.newText,
                        List.of(),
                        node.alignedRequirementIds,
                        "COORDINATED",
                        node.entrySummary == null ? join(node.improvementReasons) : node.entrySummary,
                        cited));
            }
        }
        return new V3EntryRewriteVariant(
                type,
                V3RewriteDecision.REWRITE,
                nodes,
                raw.entrySummary,
                List.of(),
                List.of(),
                List.of(),
                new V3SelfCheck(raw.selfCheckOk, raw.selfCheckNotes));
    }

    private static Set<String> allowedNodeIds(V3EntryRewriteRequest request) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (V3WritableNode node : request.writableNodes()) {
            ids.add(node.nodeId());
        }
        return ids;
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("; ", values);
    }

    private record Parsed(ModelResponse response, Integer promptTokens, Integer completionTokens) {
    }

    public static class ModelResponse {
        public ModelVariant clarityFirst;
        public ModelVariant jdTargeted;
    }

    public static class ModelVariant {
        public String decision;
        public List<ModelNode> rewrittenNodes;
        public String entrySummary = "";
        public boolean selfCheckOk = true;
        public String selfCheckNotes = "";
    }

    public static class ModelNode {
        public String nodeId;
        public String newText;
        public List<String> alignedRequirementIds;
        public List<String> improvementReasons;
        public List<String> citedSourceNodeIds;
        public String entrySummary;
    }
}
