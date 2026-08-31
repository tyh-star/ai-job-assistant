package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.V3ReviewNode;
import aijob.dto.V3SkillReview;
import aijob.util.ConservativeEvidencePacket;
import aijob.util.EvidenceTopics;
import aijob.util.TermAliasNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Projects USER_CONFIRMED hard skills into an existing Skills section only.
 * Metrics, duties, inferred tools, and JD-only skills are never added.
 */
public final class V3SkillsProjector {

    static final List<String> PROJECTABLE_LONGEST_FIRST = List.of(
            "object-oriented programming",
            "java doc string",
            "spring boot",
            "restful apis",
            "restful api",
            "rest apis",
            "rest api",
            "c/c++",
            "javascript",
            "typescript",
            "nestjs",
            "kafka",
            "nx",
            "postgresql",
            "mongodb",
            "kubernetes",
            "junit",
            "mockito",
            "pytest",
            "selenium",
            "gradle",
            "maven",
            "mysql",
            "python",
            "docker",
            "redis",
            "html",
            "css",
            "java",
            "vue",
            "react",
            "angular",
            "spring",
            "sql",
            "aws",
            "git",
            "oop"
    );
    private static final Set<String> FORBIDDEN = Set.of(
            "communication", "teamwork", "leadership", "agile",
            "maintain", "maintained", "maintaining", "maintenance",
            "deploy", "deployed", "deployment",
            "develop", "developed", "implement", "implemented",
            "design", "designed", "metric", "performance", "throughput", "latency"
    );
    private static final Pattern METRIC_SHAPE = Pattern.compile(
            "(?i)\\b\\d[\\d,]*(?:\\.\\d+)?\\s*(?:ms|s|sec|users?|requests?|qps|rps|combinations?|tps)\\b");

    private V3SkillsProjector() {
    }

    public record Result(
            List<TailorChange> changes,
            List<V3SkillReview> reviews,
            Map<Long, List<String>> skillKeysByEvidenceId,
            boolean sectionPresent,
            boolean writablePresent
    ) {
        public Result {
            changes = changes == null ? List.of() : List.copyOf(changes);
            reviews = reviews == null ? List.of() : List.copyOf(reviews);
            skillKeysByEvidenceId = skillKeysByEvidenceId == null ? Map.of() : Map.copyOf(skillKeysByEvidenceId);
        }

        public static Result none(boolean sectionPresent, boolean writablePresent) {
            return new Result(List.of(), List.of(), Map.of(), sectionPresent, writablePresent);
        }
    }

    public static Result project(List<ResumeDocumentNode> nodes, List<V3UserEvidence> evidence) {
        V3SkillsSection.Snapshot section = V3SkillsSection.find(nodes);
        Map<Long, List<String>> byEvidence = new LinkedHashMap<>();
        LinkedHashSet<String> additions = new LinkedHashSet<>();
        LinkedHashSet<Long> evidenceIds = new LinkedHashSet<>();
        List<V3UserEvidence> items = evidence == null ? List.of() : evidence;
        if (!section.writablePresent()) {
            return Result.none(section.present(), false);
        }

        List<String> existing = existingSkills(section.writable());
        for (V3UserEvidence item : items) {
            List<Extracted> extracted = extract(item);
            List<String> used = new ArrayList<>();
            for (Extracted skill : extracted) {
                if (containsSkill(existing, skill.display()) || containsSkill(new ArrayList<>(additions), skill.display())) {
                    continue;
                }
                additions.add(skill.display());
                used.add(skill.display());
            }
            if (!used.isEmpty() && item.evidenceItemId() != null) {
                byEvidence.put(item.evidenceItemId(), List.copyOf(used));
                evidenceIds.add(item.evidenceItemId());
            }
        }
        if (additions.isEmpty()) {
            return Result.none(section.present(), true);
        }
        V3WritableNode target = section.writable().get(section.writable().size() - 1);
        String proposed = appendSkills(target.originalText(), additions);
        TailorChange change = new TailorChange();
        change.setNodeId(target.nodeId());
        change.setOriginalText(target.originalText());
        change.setNewText(proposed);
        change.setApplied(false);
        change.setRequiresConfirmation(true);
        change.setEvidenceType(TailorChange.EVIDENCE_VERIFIED);
        change.setEvidenceNodeIds(List.of(target.nodeId()));
        change.setEvidenceQuote(target.originalText().length() <= 80
                ? target.originalText()
                : target.originalText().substring(0, 80));
        change.setUpliftType("SUBSTANTIVE_REWRITE");
        change.setOpportunityType("CLARITY");
        change.setReason("V3 ENHANCED skills projection");
        change.setReasonZh("V3 ENHANCED 技能同步");
        String why = whyBetter(additions, items);
        change.setImprovementSummaryEn(why);
        change.setImprovementSummaryZh(why);

        List<V3ReviewNode> originalNodes = new ArrayList<>();
        List<V3ReviewNode> proposedNodes = new ArrayList<>();
        for (V3WritableNode node : section.writable()) {
            originalNodes.add(new V3ReviewNode(node.nodeId(), node.originalText()));
            proposedNodes.add(new V3ReviewNode(
                    node.nodeId(),
                    node.nodeId().equals(target.nodeId()) ? proposed : node.originalText()));
        }
        V3SkillReview review = new V3SkillReview();
        review.setSkillReviewId(V3SkillReview.REVIEW_ID);
        review.setOriginalSkills(joinSkills(section.writable(), false, target.nodeId(), proposed));
        review.setProposedSkills(joinSkills(section.writable(), true, target.nodeId(), proposed));
        review.setAddedSkills(orderedAdditions(additions));
        review.setEvidenceItemIds(new ArrayList<>(evidenceIds));
        review.setWhyBetter(why);
        review.setAdmitted(true);
        review.setOriginalNodes(originalNodes);
        review.setProposedNodes(proposedNodes);
        return new Result(List.of(change), List.of(review), byEvidence, true, true);
    }

    static List<Extracted> extract(V3UserEvidence item) {
        if (item == null || !item.confirmed()) {
            return List.of();
        }
        String topic = item.topicKey();
        if (EvidenceTopics.isEnhancedMetricTopic(topic) || EvidenceTopics.ENHANCED_MAINTAIN.equals(topic)) {
            return List.of();
        }
        String text = item.displayText();
        if (text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> found = new LinkedHashSet<>();
        String remaining = text.toLowerCase(Locale.ROOT);
        for (String phrase : PROJECTABLE_LONGEST_FIRST) {
            if (TermAliasNormalizer.containsPhrase(remaining, phrase) && allowedSkill(phrase)) {
                found.add(skillDisplay(phrase));
                remaining = remaining.replace(phrase, " ");
            }
        }
        for (String tech : ConservativeEvidencePacket.extractNormalizedTech(text)) {
            if (allowedSkill(tech)) {
                found.add(skillDisplay(tech));
            }
        }
        List<Extracted> extracted = new ArrayList<>();
        for (String display : found) {
            if (!allowedSkill(display)) {
                continue;
            }
            extracted.add(new Extracted(TermAliasNormalizer.canonicalKey(display), display));
        }
        return extracted;
    }

    static boolean isProjectableSkillTopic(V3UserEvidence item) {
        return item != null && item.confirmed() && !extract(item).isEmpty();
    }

    static boolean looksLikeMetricEvidence(V3UserEvidence item) {
        if (item == null) {
            return false;
        }
        if (EvidenceTopics.isEnhancedMetricTopic(item.topicKey())) {
            return true;
        }
        return METRIC_SHAPE.matcher(item.displayText()).find() && extract(item).isEmpty();
    }

    private static boolean allowedSkill(String term) {
        String key = TermAliasNormalizer.canonicalKey(term);
        if (key.isEmpty() || FORBIDDEN.contains(key)) {
            return false;
        }
        if (key.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.' || ch == ',')) {
            return false;
        }
        return PROJECTABLE_LONGEST_FIRST.stream().anyMatch(phrase -> TermAliasNormalizer.sameTerm(phrase, term))
                || ConservativeEvidencePacket.extractNormalizedTech(term)
                .contains(aijob.util.EvidenceText.normalize(term));
    }

    private static List<String> existingSkills(List<V3WritableNode> writable) {
        List<String> skills = new ArrayList<>();
        for (V3WritableNode node : writable) {
            skills.addAll(splitSkills(node.originalText()));
        }
        return skills;
    }

    static List<String> splitSkills(String text) {
        List<String> skills = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return skills;
        }
        String body = text;
        int colon = text.indexOf(':');
        if (colon > 0 && colon < 40) {
            body = text.substring(colon + 1);
        }
        for (String part : body.split("[,;•●|]")) {
            String skill = part.strip();
            if (!skill.isBlank() && !skill.equals("-")) {
                skills.add(skill);
            }
        }
        return skills;
    }

    private static boolean containsSkill(List<String> existing, String candidate) {
        for (String skill : existing) {
            if (TermAliasNormalizer.sameTerm(skill, candidate)) {
                return true;
            }
        }
        return false;
    }

    static String appendSkills(String original, Set<String> additions) {
        String base = original == null ? "" : original;
        List<String> existing = splitSkills(base);
        StringBuilder out = new StringBuilder(base);
        for (String addition : orderedAdditions(additions)) {
            String display = skillDisplay(addition);
            if (display.isBlank() || containsSkill(existing, display)) {
                continue;
            }
            if (out.length() > 0) {
                if (out.toString().endsWith(",")) {
                    out.append(' ');
                } else if (!out.toString().endsWith(", ")) {
                    out.append(", ");
                }
            }
            out.append(display);
            existing.add(display);
        }
        return out.toString();
    }

    static String skillDisplay(String term) {
        String key = TermAliasNormalizer.canonicalKey(term);
        return switch (key) {
            case "nx" -> "Nx";
            case "restful api" -> "REST APIs";
            case "typescript" -> "TypeScript";
            case "kafka" -> "Kafka";
            default -> TermAliasNormalizer.displayName(term);
        };
    }

    private static List<String> orderedAdditions(Set<String> additions) {
        List<String> preferred = List.of("Kafka", "REST APIs", "TypeScript", "Nx");
        LinkedHashSet<String> remaining = new LinkedHashSet<>();
        if (additions != null) {
            remaining.addAll(additions);
        }
        List<String> ordered = new ArrayList<>();
        for (String skill : preferred) {
            String match = null;
            for (String addition : remaining) {
                if (TermAliasNormalizer.sameTerm(addition, skill)) {
                    match = addition;
                    break;
                }
            }
            if (match != null) {
                ordered.add(skillDisplay(match));
                remaining.remove(match);
            }
        }
        for (String addition : remaining) {
            ordered.add(skillDisplay(addition));
        }
        return ordered;
    }

    private static String joinSkills(
            List<V3WritableNode> writable,
            boolean proposed,
            String targetId,
            String proposedText) {
        List<String> lines = new ArrayList<>();
        for (V3WritableNode node : writable) {
            if (proposed && node.nodeId().equals(targetId)) {
                lines.add(proposedText);
            } else {
                lines.add(node.originalText());
            }
        }
        return String.join("\n", lines);
    }

    private static String whyBetter(Set<String> additions, List<V3UserEvidence> items) {
        String names = String.join(", ", additions);
        String title = "";
        for (V3UserEvidence item : items) {
            if (item != null && item.evidenceItemId() != null && !extract(item).isEmpty()) {
                title = friendlyEntryTitle(item.storedEntryKey());
                break;
            }
        }
        if (title.isBlank()) {
            return "Adds confirmed " + names + " to Technical Skills.";
        }
        return "Adds " + names + " from your confirmed " + title + " detail.";
    }

    static String friendlyEntryTitle(String entryKey) {
        if (entryKey == null || entryKey.isBlank()) {
            return "";
        }
        int colon = entryKey.indexOf(':');
        String slug = colon < 0 ? entryKey.strip() : entryKey.substring(colon + 1).strip();
        if (slug.isBlank()) {
            return "";
        }
        String[] parts = slug.replace('-', ' ').split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            if (part.equalsIgnoreCase("smartpc")) {
                out.append("SmartPC");
            } else {
                out.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    out.append(part.substring(1));
                }
            }
        }
        return out.toString();
    }

    record Extracted(String key, String display) {
    }
}
