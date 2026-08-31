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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class OpenAiV3ResumeLanguageReviewer implements V3ResumeLanguageReviewer {

    private static final Logger log = LoggerFactory.getLogger(OpenAiV3ResumeLanguageReviewer.class);

    private static final String INSTRUCTIONS = """
            Compare resume entry versions and choose exactly one option per entry:
            ORIGINAL, CLARITY_FIRST, JD_TARGETED, HYBRID_1, HYBRID_2, or REJECT_ALL_AI.
            This is one call for the whole resume. Do not write a new version.
            Do not restore a fact-invalid rewrite; those variants are omitted on purpose.
            You may only select a listed version or reject. You cannot invent text.

            Choose the version that sounds like a professional human resume, not AI copy.
            Prefer the original when no AI or hybrid version is clearly more natural.
            Prefer a hybrid when it keeps facts and combines the most natural bullets.
            Reject empty, puffed, or nominalized phrasing.
            Reject versions that weaken original facts or add repetition.
            Prefer the version that highlights real contribution and JD relevance.
            Prefer "using Java and Spring Boot" over "using Java Spring Boot".
            Prefer "used" over "utilizing" and "to AWS" over "onto AWS".
            A synonym swap is not enough to beat the original.

            """ + V3LanguageGuide.TEXT;

    private final OpenAIClient openAIClient;
    private final String model;

    public OpenAiV3ResumeLanguageReviewer(
            OpenAIClient openAIClient,
            @Value("${openai.model}") String model) {
        this.openAIClient = openAIClient;
        this.model = model;
    }

    @Override
    public List<V3ResumeLanguageReviewer.Choice> review(
            String jobDescription,
            List<V3ResumeLanguageReviewer.EntryComparison> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        try {
            return toChoices(entries, requestStructured(buildInput(jobDescription, entries)));
        } catch (RuntimeException ex) {
            log.warn("V3 language review failed: {}", ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private ModelResponse requestStructured(String input) {
        StructuredResponseCreateParams<ModelResponse> params =
                ResponseCreateParams.builder()
                        .model(model)
                        .instructions(INSTRUCTIONS)
                        .input(input)
                        .text(ModelResponse.class)
                        .build();
        StructuredResponse<ModelResponse> created = openAIClient.responses().create(params);
        Optional<ResponseUsage> usage = created.usage();
        if (usage == null || usage.isEmpty()) {
            log.info("v3.language.review promptTokens=unknown completionTokens=unknown");
        } else {
            log.info(
                    "v3.language.review promptTokens={} completionTokens={}",
                    usage.get().inputTokens(),
                    usage.get().outputTokens());
        }
        return created.output()
                .stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .findFirst()
                .orElseThrow(() -> new AiServiceException("AI analysis is temporarily unavailable"));
    }

    private static String buildInput(
            String jobDescription,
            List<V3ResumeLanguageReviewer.EntryComparison> entries) {
        StringBuilder text = new StringBuilder();
        text.append("JOB DESCRIPTION:\n").append(jobDescription == null ? "" : jobDescription).append('\n');
        for (V3ResumeLanguageReviewer.EntryComparison entry : entries) {
            text.append("ENTRY ").append(entry.canonicalEntryKey()).append('\n');
            text.append("ORIGINAL:\n").append(entry.originalEntryText()).append('\n');
            appendScore(text, "ORIGINAL_SCORE", entry.originalScore());
            if (entry.clarityFirst() != null) {
                text.append("CLARITY_FIRST:\n")
                        .append(String.join("\n", entry.clarityFirst().bulletsInNodeOrder(List.of())))
                        .append('\n');
                appendVariant(text, entry.clarityFirst());
                appendScore(text, "CLARITY_SCORE", entry.clarityScore());
            } else {
                text.append("CLARITY_FIRST: unavailable (failed fact gate)\n");
            }
            if (entry.jdTargeted() != null) {
                text.append("JD_TARGETED:\n");
                appendVariant(text, entry.jdTargeted());
                appendScore(text, "TARGETED_SCORE", entry.targetedScore());
            } else {
                text.append("JD_TARGETED: unavailable (failed fact gate)\n");
            }
            if (entry.hybrid1() != null) {
                text.append("HYBRID_1 sources=").append(entry.hybrid1Sources()).append('\n');
                appendVariant(text, entry.hybrid1());
                appendScore(text, "HYBRID_1_SCORE", entry.hybrid1Score());
            } else {
                text.append("HYBRID_1: unavailable (failed fact gate)\n");
            }
            if (entry.hybrid2() != null) {
                text.append("HYBRID_2 sources=").append(entry.hybrid2Sources()).append('\n');
                appendVariant(text, entry.hybrid2());
                appendScore(text, "HYBRID_2_SCORE", entry.hybrid2Score());
            } else {
                text.append("HYBRID_2: unavailable (failed fact gate)\n");
            }
            text.append("REQUIREMENTS:\n");
            for (V3RankedRequirement requirement : entry.requirements()) {
                text.append(requirement.id()).append(" [").append(requirement.coverage()).append("] ")
                        .append(requirement.text()).append('\n');
            }
        }
        return text.toString();
    }

    private static void appendVariant(StringBuilder text, V3EntryRewriteVariant variant) {
        for (V3RewrittenNode node : variant.rewrittenNodes()) {
            text.append("- ").append(node.newText()).append('\n');
        }
    }

    private static void appendScore(StringBuilder text, String label, V3QualityScore score) {
        if (score == null) {
            text.append(label).append(": n/a\n");
            return;
        }
        text.append(label).append(" total=").append(score.total())
                .append(" fact=").append(score.factIntegrity())
                .append(" jd=").append(score.jdRelevance())
                .append(" uplift=").append(score.materialUplift())
                .append(" clarity=").append(score.clarityNaturalness())
                .append('\n');
    }

    private static List<V3ResumeLanguageReviewer.Choice> toChoices(
            List<V3ResumeLanguageReviewer.EntryComparison> entries,
            ModelResponse response) {
        List<V3ResumeLanguageReviewer.Choice> choices = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            V3ResumeLanguageReviewer.EntryComparison entry = entries.get(i);
            ModelChoice found = matchModelChoice(entry, response, i, entries.size());
            V3SelectionChoice selection = parseSelection(found == null ? "" : found.selection);
            if (selection == V3SelectionChoice.CLARITY_FIRST && entry.clarityFirst() == null) {
                selection = V3SelectionChoice.ORIGINAL;
            }
            if (selection == V3SelectionChoice.JD_TARGETED && entry.jdTargeted() == null) {
                selection = V3SelectionChoice.ORIGINAL;
            }
            if (selection == V3SelectionChoice.HYBRID_1 && entry.hybrid1() == null) {
                selection = V3SelectionChoice.ORIGINAL;
            }
            if (selection == V3SelectionChoice.HYBRID_2 && entry.hybrid2() == null) {
                selection = V3SelectionChoice.ORIGINAL;
            }
            String reason = found == null ? "missing-choice-original" : found.reason;
            choices.add(new V3ResumeLanguageReviewer.Choice(entry.canonicalEntryKey(), selection, reason));
        }
        return choices;
    }

    static ModelChoice matchModelChoice(
            V3ResumeLanguageReviewer.EntryComparison entry,
            ModelResponse response,
            int index,
            int entryCount) {
        if (entry == null || response == null || response.entries == null) {
            return null;
        }
        String want = entry.canonicalEntryKey();
        for (ModelChoice item : response.entries) {
            if (item != null && keysMatch(want, item.canonicalEntryKey)) {
                return item;
            }
        }
        if (response.entries.size() == entryCount && index >= 0 && index < response.entries.size()) {
            return response.entries.get(index);
        }
        return null;
    }

    static boolean keysMatch(String expected, String actual) {
        if (expected == null || actual == null || actual.isBlank()) {
            return false;
        }
        if (expected.equals(actual) || expected.equalsIgnoreCase(actual.strip())) {
            return true;
        }
        String left = slug(expected);
        String right = slug(actual);
        return !left.isBlank() && left.equals(right);
    }

    private static String slug(String key) {
        String value = key == null ? "" : key.strip().toLowerCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1);
        }
        return value.replace('_', '-').replace(' ', '-');
    }

    static V3SelectionChoice parseSelection(String raw) {
        if (raw == null || raw.isBlank()) {
            return V3SelectionChoice.ORIGINAL;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "CLARITY_FIRST", "CLARITY" -> V3SelectionChoice.CLARITY_FIRST;
            case "JD_TARGETED", "TARGETED" -> V3SelectionChoice.JD_TARGETED;
            case "HYBRID_1", "HYBRID1" -> V3SelectionChoice.HYBRID_1;
            case "HYBRID_2", "HYBRID2" -> V3SelectionChoice.HYBRID_2;
            case "REJECT_ALL_AI", "REJECT" -> V3SelectionChoice.REJECT_ALL_AI;
            default -> V3SelectionChoice.ORIGINAL;
        };
    }

    public static class ModelResponse {
        public List<ModelChoice> entries;
    }

    public static class ModelChoice {
        public String canonicalEntryKey;
        public String selection;
        public String reason;
    }
}
