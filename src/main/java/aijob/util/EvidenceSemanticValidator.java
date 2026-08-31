package aijob.util;

import aijob.dto.AnswerStatus;
import aijob.dto.EntryOption;
import aijob.dto.EvidenceReasonCode;
import aijob.dto.EvidenceValidationStatus;
import aijob.dto.RequirementCategory;
import aijob.dto.TailorAnalysisRequirement;
import aijob.entity.ResumeEvidenceItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic Evidence Bank answer validation. No OpenAI.
 */
public final class EvidenceSemanticValidator {

    private static final Set<String> FILLER = Set.of(
            "i", "used", "use", "with", "it", "the", "a", "an", "this", "have", "in", "my", "me"
    );
    private static final Set<String> VAGUE = Set.of(
            "yes", "y", "true", "ok", "used it", "worked with it", "i used it",
            "i have it", "have it", "used this", "worked with this"
    );
    private static final Pattern NEVER_USED = Pattern.compile(
            "(?i)\\b(?:never used|not used|do not use|don't use|no experience|did not use)\\b"
    );
    private static final Pattern DEBUG_PHRASE = Pattern.compile(
            "(?i)\\b(?:fixed a bug|fixed an issue|resolved an issue|resolved a bug|fixed an authentication bug)\\b"
    );
    private static final Map<String, List<String>> SKILL_ALIASES = Map.of(
            "docker", List.of("docker", "dockerfile", "containerized", "container", "containers"),
            "mysql", List.of("mysql", "database", "query", "queries", "persistence"),
            "maven", List.of("maven", "pom.xml", "pom", "build dependency")
    );
    private static final Set<String> GENERIC_SOFT = Set.of(
            "i have communication skills",
            "i have teamwork skills",
            "good communication",
            "strong communication skills",
            "i am a team player"
    );

    private EvidenceSemanticValidator() {
    }

    public record Result(boolean valid, String reasonCode, List<String> missingActions) {
        public static Result ok() {
            return new Result(true, null, List.of());
        }

        public static Result ok(List<String> missingActions) {
            return new Result(true, null, missingActions == null ? List.of() : List.copyOf(missingActions));
        }

        public static Result invalid(String reasonCode) {
            return new Result(false, reasonCode, List.of());
        }

        public String validationStatus() {
            return valid ? EvidenceValidationStatus.VALID : EvidenceValidationStatus.NEEDS_REVIEW;
        }
    }

    public record Input(
            String topic,
            String category,
            String requirement,
            String answerStatus,
            String context,
            String action,
            String result,
            String metric,
            String entryKey,
            List<String> relatedNodeIds,
            EntryOption selectedEntry,
            ClaimLedger ledger,
            Map<String, AnalysisEvidenceSource> registry
    ) {
        public static Input fromItem(
                ResumeEvidenceItem item,
                TailorAnalysisRequirement requirement,
                ClaimLedger ledger,
                Map<String, AnalysisEvidenceSource> registry) {
            if (item == null) {
                return new Input(null, null, requirement == null ? null : requirement.getRequirement(),
                        null, null, null, null, null, ResumeEntryCatalog.GENERAL, List.of(),
                        null, ledger, registry);
            }
            return new Input(
                    item.getTopicLabel() == null ? item.getTopicKey() : item.getTopicLabel(),
                    item.getCategory(),
                    requirement == null ? item.getTopicLabel() : requirement.getRequirement(),
                    item.getAnswerStatus(),
                    item.getContextText(),
                    item.getActionText(),
                    item.getResultText(),
                    item.getMetricText(),
                    item.getEntryKey(),
                    List.of(),
                    null,
                    ledger,
                    registry
            );
        }
    }

    public static Result validate(Input input) {
        if (input == null) {
            return Result.invalid(EvidenceReasonCode.EVIDENCE_TOO_VAGUE);
        }
        Result structural = validateNodes(input);
        if (!structural.valid()) {
            return structural;
        }
        return validateAnswer(input);
    }

    public static Result validateNodes(Input input) {
        List<String> related = input.relatedNodeIds() == null ? List.of() : input.relatedNodeIds();
        Map<String, AnalysisEvidenceSource> registry = input.registry() == null ? Map.of() : input.registry();
        ClaimLedger ledger = input.ledger();
        boolean general = input.entryKey() == null
                || input.entryKey().isBlank()
                || ResumeEntryCatalog.GENERAL.equals(input.entryKey());
        if (related.isEmpty()) {
            return Result.ok();
        }
        List<String> known = new ArrayList<>();
        for (String nodeId : related) {
            if (nodeId == null || nodeId.isBlank()) {
                continue;
            }
            if (!registry.containsKey(nodeId)) {
                return Result.invalid(EvidenceReasonCode.EVIDENCE_NODE_NOT_FOUND);
            }
            known.add(nodeId);
        }
        if (known.isEmpty()) {
            return Result.ok();
        }
        if (ledger != null) {
            for (String nodeId : known) {
                if (ledger.entryOf(nodeId) == null) {
                    return Result.invalid(EvidenceReasonCode.EVIDENCE_ENTRY_MISMATCH);
                }
            }
            String first = known.get(0);
            for (String nodeId : known) {
                if (!ledger.sameEntry(first, nodeId)) {
                    return Result.invalid(EvidenceReasonCode.EVIDENCE_CROSS_ENTRY);
                }
            }
        }
        if (!general && input.selectedEntry() != null) {
            Set<String> entryIds = new LinkedHashSet<>(
                    input.selectedEntry().getNodeIds() == null ? List.of() : input.selectedEntry().getNodeIds());
            for (String nodeId : known) {
                if (!entryIds.contains(nodeId)) {
                    return Result.invalid(EvidenceReasonCode.EVIDENCE_CROSS_ENTRY);
                }
            }
        }
        return Result.ok();
    }

    public static Result validateAnswer(Input input) {
        String status = input.answerStatus() == null ? "" : input.answerStatus().strip();
        String text = combined(input.context(), input.action(), input.result(), input.metric());
        String topic = input.topic() == null ? "" : input.topic();
        String requirement = isBlank(input.requirement()) ? topic : input.requirement();
        String category = input.category() == null ? RequirementCategory.HARD_SKILL : input.category();

        if (AnswerStatus.CONFIRMED_NOT_USED.equals(status)) {
            return Result.ok();
        }
        if (NEVER_USED.matcher(text).find()) {
            return Result.invalid(EvidenceReasonCode.EVIDENCE_CONTRADICTS_REJECTION);
        }
        if (isVague(text, topic)) {
            return Result.invalid(EvidenceReasonCode.EVIDENCE_TOO_VAGUE);
        }
        if (EvidenceTopics.isEnhancedCatalogTopic(topic)) {
            return Result.ok();
        }
        if (RequirementCategory.QUALIFICATION.equals(category)) {
            return validateQualification(text);
        }
        if (RequirementCategory.SOFT_SKILL.equals(category)) {
            return validateSoftSkill(text);
        }
        if (RequirementCategory.RESPONSIBILITY.equals(category)) {
            return validateResponsibility(requirement, topic, text);
        }
        return validateHardSkill(topic, text);
    }

    public static Result validateStored(
            ResumeEvidenceItem item,
            TailorAnalysisRequirement requirement) {
        return validateAnswer(Input.fromItem(item, requirement, null, Map.of()));
    }

    public static boolean isValidConfirmation(ResumeEvidenceItem item, TailorAnalysisRequirement requirement) {
        if (item == null || !AnswerStatus.CONFIRMED_USED.equals(item.getAnswerStatus())) {
            return false;
        }
        Result result = validateStored(item, requirement);
        return result.valid() && result.missingActions().isEmpty();
    }

    public static boolean isValidRejection(ResumeEvidenceItem item) {
        return item != null
                && AnswerStatus.CONFIRMED_NOT_USED.equals(item.getAnswerStatus())
                && validateStored(item, null).valid();
    }

    public static List<String> missingActions(
            TailorAnalysisRequirement requirement,
            List<ResumeEvidenceItem> items) {
        ResponsibilityConcept concept = conceptOf(requirement);
        if (concept == null || concept.requiredActions().isEmpty()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        if (requirement != null && requirement.getEvidenceQuotes() != null) {
            found.addAll(ActionConceptRegistry.actionsIn(String.join(" ", requirement.getEvidenceQuotes())));
        }
        if (items != null) {
            for (ResumeEvidenceItem item : items) {
                if (item == null || !AnswerStatus.CONFIRMED_USED.equals(item.getAnswerStatus())) {
                    continue;
                }
                if (!validateStored(item, requirement).valid()) {
                    continue;
                }
                found.addAll(ActionConceptRegistry.actionsIn(combined(
                        item.getContextText(), item.getActionText(), item.getResultText(), item.getMetricText())));
                found.addAll(extraResponsibilityActions(combined(
                        item.getContextText(), item.getActionText(), item.getResultText(), item.getMetricText())));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String action : concept.requiredActions()) {
            if (!found.contains(action) && !foundContainsAlias(found, action)) {
                missing.add(action);
            }
        }
        return missing;
    }

    private static Result validateHardSkill(String topic, String text) {
        if (matchesSkill(text, topic)) {
            return Result.ok();
        }
        if (looksLikeUnrelatedArchitecture(text)) {
            return Result.invalid(EvidenceReasonCode.EVIDENCE_TOPIC_MISMATCH);
        }
        return Result.invalid(EvidenceReasonCode.EVIDENCE_TOPIC_MISMATCH);
    }

    private static Result validateResponsibility(String requirement, String topic, String text) {
        ResponsibilityConcept concept = ResponsibilityConceptExtractor.extract(requirement, List.of());
        if (!concept.hasCoreActions()) {
            concept = ResponsibilityConceptExtractor.extract(topic, List.of());
        }
        Set<String> found = new LinkedHashSet<>(ActionConceptRegistry.actionsIn(text));
        found.addAll(extraResponsibilityActions(text));
        List<String> missing = new ArrayList<>();
        for (String action : concept.requiredActions()) {
            if (!found.contains(action)) {
                missing.add(action);
            }
        }
        boolean anyRequired = concept.requiredActions().isEmpty() || missing.size() < concept.requiredActions().size();
        if (!anyRequired) {
            if (looksLikeUnrelatedArchitecture(text) || hasOffTopicDuty(text, concept)) {
                return Result.invalid(EvidenceReasonCode.EVIDENCE_TOPIC_MISMATCH);
            }
            return Result.invalid(EvidenceReasonCode.EVIDENCE_ACTION_MISSING);
        }
        if (ObjectDomainRegistry.conflicts(concept.requiredObjectOrDomain(), ObjectDomainRegistry.domainsIn(text))) {
            return Result.invalid(EvidenceReasonCode.EVIDENCE_DOMAIN_MISMATCH);
        }
        if (collaborateWithoutCollaboration(concept, text)) {
            return Result.invalid(EvidenceReasonCode.EVIDENCE_ACTION_MISSING);
        }
        return Result.ok(missing);
    }

    private static Result validateQualification(String text) {
        String haystack = EvidenceText.normalize(text);
        if (haystack.contains("university")
                || haystack.contains("college")
                || haystack.contains("degree")
                || haystack.contains("bachelor")
                || haystack.contains("master")
                || haystack.contains("diploma")
                || haystack.contains("present")
                || haystack.matches(".*\\b(?:19|20)\\d{2}\\b.*")) {
            return Result.ok();
        }
        return Result.invalid(EvidenceReasonCode.EVIDENCE_TOO_VAGUE);
    }

    private static Result validateSoftSkill(String text) {
        String normalized = EvidenceText.normalize(text);
        if (GENERIC_SOFT.contains(normalized) || isVague(text, "communication")) {
            return Result.invalid(EvidenceReasonCode.EVIDENCE_TOO_VAGUE);
        }
        if (ActionConceptRegistry.actionsIn(text).isEmpty() && text.split("\\s+").length < 8) {
            return Result.invalid(EvidenceReasonCode.EVIDENCE_TOO_VAGUE);
        }
        return Result.ok();
    }

    private static boolean matchesSkill(String text, String topic) {
        if (TermAliasNormalizer.matches(text, topic)) {
            return true;
        }
        String key = EvidenceText.normalize(topic);
        List<String> aliases = SKILL_ALIASES.getOrDefault(key, List.of());
        for (String alias : aliases) {
            if (TermAliasNormalizer.containsPhrase(EvidenceText.normalize(text), alias)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVague(String text, String topic) {
        String normalized = EvidenceText.normalize(text);
        if (normalized.isBlank()) {
            return true;
        }
        if (VAGUE.contains(normalized)) {
            return true;
        }
        String withoutTopic = normalized;
        if (topic != null && !topic.isBlank()) {
            withoutTopic = normalized.replace(EvidenceText.normalize(topic), " ").replaceAll("\\s+", " ").strip();
        }
        if (VAGUE.contains(withoutTopic) || withoutTopic.isBlank()) {
            return true;
        }
        boolean onlyFiller = !withoutTopic.isBlank();
        for (String token : withoutTopic.split("[^a-z0-9+#]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (!FILLER.contains(token)) {
                onlyFiller = false;
                break;
            }
        }
        return onlyFiller;
    }

    private static boolean looksLikeUnrelatedArchitecture(String text) {
        return TermAliasNormalizer.matches(text, "mvc")
                && !ActionConceptRegistry.contains(text, ActionConceptRegistry.DEBUG)
                && extraResponsibilityActions(text).isEmpty();
    }

    private static boolean hasOffTopicDuty(String text, ResponsibilityConcept concept) {
        Set<String> found = ActionConceptRegistry.actionsIn(text);
        if (found.isEmpty()) {
            return false;
        }
        for (String required : concept.requiredActions()) {
            if (found.contains(required)) {
                return false;
            }
        }
        return found.contains(ActionConceptRegistry.DESIGN)
                || found.contains(ActionConceptRegistry.IMPLEMENT);
    }

    private static boolean collaborateWithoutCollaboration(ResponsibilityConcept concept, String text) {
        if (!concept.requiredActions().contains(ActionConceptRegistry.COLLABORATE)) {
            return false;
        }
        String haystack = EvidenceText.normalize(text);
        return !ActionConceptRegistry.contains(text, ActionConceptRegistry.COLLABORATE)
                && (haystack.contains("understand") || haystack.contains("knowledge of"))
                && ObjectDomainRegistry.contains(text, ObjectDomainRegistry.AGILE);
    }

    private static Set<String> extraResponsibilityActions(String text) {
        Set<String> extra = new LinkedHashSet<>();
        if (DEBUG_PHRASE.matcher(text == null ? "" : text).find()
                || ActionConceptRegistry.contains(text, ActionConceptRegistry.TROUBLESHOOT)) {
            extra.add(ActionConceptRegistry.DEBUG);
        }
        return extra;
    }

    private static boolean foundContainsAlias(Set<String> found, String action) {
        return ActionConceptRegistry.DEBUG.equals(action) && found.contains(ActionConceptRegistry.TROUBLESHOOT);
    }

    private static ResponsibilityConcept conceptOf(TailorAnalysisRequirement requirement) {
        if (requirement == null || !RequirementCategory.RESPONSIBILITY.equals(requirement.getCategory())) {
            return null;
        }
        return ResponsibilityConceptExtractor.extract(requirement);
    }

    private static String combined(String... parts) {
        StringBuilder text = new StringBuilder();
        if (parts == null) {
            return "";
        }
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(part.strip());
        }
        return text.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
