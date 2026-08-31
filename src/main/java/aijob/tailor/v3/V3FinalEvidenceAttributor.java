package aijob.tailor.v3;

import aijob.util.ActionConceptRegistry;
import aijob.util.ConservativeEvidencePacket;
import aijob.util.EvidenceText;
import aijob.util.EvidenceTopics;
import aijob.util.TermAliasNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Final-output Evidence attribution. Usage is {@code proposed facts − original facts}.
 * Prompt inclusion, model inspection, and original generic tokens never count as used.
 */
public final class V3FinalEvidenceAttributor {

    private static final Pattern NUMBER = Pattern.compile("\\b\\d[\\d,]*(?:\\.\\d+)?\\b");
    private static final Pattern JSON = Pattern.compile("(?i)\\bjson\\b");
    private static final Pattern REQUEST_RESPONSE = Pattern.compile("(?i)request(?:s)?(?:/|\\s+and\\s+|\\s+)response");
    private static final Pattern VALIDATION_ERROR = Pattern.compile(
            "(?i)\\b(?:validation|error responses?|standardized error|error contract)\\b");
    private static final Pattern API_VERSION = Pattern.compile(
            "(?i)(?:/api/v\\d+|api/v\\d+|versioned(?:\\s+rest)?(?:\\s+api)?|endpoint version|api versioning)");
    private static final Pattern REST_ONLY = Pattern.compile("(?i)\\b(?:restful(?:\\s+apis?)?|rest(?:\\s+apis?)?)\\b");

    private V3FinalEvidenceAttributor() {
    }

    public static boolean usedInEntry(V3UserEvidence item, String originalText, String proposedText) {
        if (item == null || !item.confirmed() || proposedText == null || proposedText.isBlank()) {
            return false;
        }
        String original = originalText == null ? "" : originalText;
        String topic = item.topicKey();
        if (EvidenceTopics.ENHANCED_NX_MONOREPO.equals(topic)) {
            return nxUsed(original, proposedText, item.displayText());
        }
        if (EvidenceTopics.ENHANCED_API_CONTRACT.equals(topic)) {
            return apiContractUsed(original, proposedText);
        }
        if (EvidenceTopics.ENHANCED_EVENT_DRIVEN.equals(topic)) {
            return kafkaUsed(original, proposedText, item.displayText());
        }
        if (EvidenceTopics.isEnhancedMetricTopic(topic) || looksLikeMetric(item)) {
            return metricUsed(original, proposedText, item);
        }
        if (EvidenceTopics.ENHANCED_MAINTAIN.equals(topic)) {
            return ActionConceptRegistry.contains(proposedText, ActionConceptRegistry.MAINTAIN)
                    && !ActionConceptRegistry.contains(original, ActionConceptRegistry.MAINTAIN);
        }
        if (EvidenceTopics.ENHANCED_TESTING.equals(topic)) {
            return ActionConceptRegistry.contains(proposedText, ActionConceptRegistry.TEST)
                    && !ActionConceptRegistry.contains(original, ActionConceptRegistry.TEST);
        }
        if (EvidenceTopics.ENHANCED_BUILD_TOOL.equals(topic)) {
            return newTech(original, proposedText, "maven", "gradle");
        }
        if (EvidenceTopics.ENHANCED_DATA_STORE.equals(topic)) {
            return newTech(original, proposedText, "mysql", "postgresql", "mongodb", "redis", "sql");
        }
        return genericDelta(original, proposedText, item);
    }

    public static List<V3UserEvidence> usedInEntry(
            List<V3UserEvidence> evidence,
            String entryKey,
            String originalText,
            String proposedText) {
        List<V3UserEvidence> used = new ArrayList<>();
        for (V3UserEvidence item : evidence == null ? List.<V3UserEvidence>of() : evidence) {
            if (item == null || !item.confirmed()) {
                continue;
            }
            if (entryKey != null && !entryKey.isBlank() && !entryKey.equals(item.storedEntryKey())) {
                continue;
            }
            if (usedInEntry(item, originalText, proposedText)) {
                used.add(item);
            }
        }
        return used;
    }

    public static List<Long> usedEntryIds(
            List<V3UserEvidence> evidence,
            String entryKey,
            String originalText,
            String proposedText) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (V3UserEvidence item : usedInEntry(evidence, entryKey, originalText, proposedText)) {
            if (item.evidenceItemId() != null) {
                ids.add(item.evidenceItemId());
            }
        }
        return List.copyOf(ids);
    }

    public static List<V3UserEvidence> missingRequired(
            List<V3UserEvidence> required,
            String originalText,
            String proposedText) {
        List<V3UserEvidence> missing = new ArrayList<>();
        for (V3UserEvidence item : required == null ? List.<V3UserEvidence>of() : required) {
            if (!usedInEntry(item, originalText, proposedText)) {
                missing.add(item);
            }
        }
        return missing;
    }

    public static boolean usedInSkills(
            V3UserEvidence item,
            List<String> addedSkills,
            String originalSkills) {
        if (item == null || !item.confirmed() || addedSkills == null || addedSkills.isEmpty()) {
            return false;
        }
        List<V3SkillsProjector.Extracted> extracted = V3SkillsProjector.extract(item);
        if (extracted.isEmpty()) {
            return false;
        }
        for (V3SkillsProjector.Extracted skill : extracted) {
            if (!containsSkill(addedSkills, skill.display())) {
                continue;
            }
            if (containsSkill(V3SkillsProjector.splitSkills(originalSkills), skill.display())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean nxUsed(String original, String proposed, String evidenceText) {
        boolean core = newlyPresent(original, proposed, evidenceText, "nx", "monorepo", "mono-repo", "monorepos");
        int extras = 0;
        if (newlyPresent(original, proposed, evidenceText, "shared librar", "shared lib")) {
            extras++;
        }
        if (newlyPresent(original, proposed, evidenceText, "dependency graph")) {
            extras++;
        }
        if (newlyPresent(original, proposed, evidenceText, "affected build", "affected test")) {
            extras++;
        }
        return core && extras >= 1;
    }

    private static boolean kafkaUsed(String original, String proposed, String evidenceText) {
        boolean core = newlyPresent(original, proposed, evidenceText, "kafka", "event-driven", "event driven");
        int extras = 0;
        if (newlyPresent(original, proposed, evidenceText,
                "retries", "retry", "bounded retries", "dead-letter", "dead letter", "dlt")) {
            extras++;
        }
        if (newlyPresent(original, proposed, evidenceText, "idempotent", "idempotency")) {
            extras++;
        }
        if (newlyPresent(original, proposed, evidenceText, "schema version", "schema/version", "schema evolution",
                "versioned event", "event schema")) {
            extras++;
        }
        return core && extras >= 2;
    }

    private static boolean apiContractUsed(String original, String proposed) {
        boolean json = JSON.matcher(proposed).find() && !JSON.matcher(original).find();
        boolean requestResponse = REQUEST_RESPONSE.matcher(proposed).find()
                && !REQUEST_RESPONSE.matcher(original).find();
        boolean validation = VALIDATION_ERROR.matcher(proposed).find()
                && !VALIDATION_ERROR.matcher(original).find();
        boolean version = API_VERSION.matcher(proposed).find() && !API_VERSION.matcher(original).find();
        int distinctive = 0;
        if (json || requestResponse) {
            distinctive++;
        }
        if (validation) {
            distinctive++;
        }
        if (version) {
            distinctive++;
        }
        if (distinctive >= 2) {
            return true;
        }
        if (REST_ONLY.matcher(proposed).find()) {
            return false;
        }
        return false;
    }

    private static boolean metricUsed(String original, String proposed, V3UserEvidence item) {
        String surface = item.displayText();
        Matcher numbers = NUMBER.matcher(surface);
        boolean any = false;
        while (numbers.find()) {
            any = true;
            String value = numbers.group();
            String compact = value.replace(",", "");
            if (!containsNumber(proposed, value, compact) || containsNumber(original, value, compact)) {
                return false;
            }
        }
        if (!any) {
            return false;
        }
        String lowerProposed = proposed.toLowerCase(Locale.ROOT);
        if ((lowerProposed.contains("testing") || lowerProposed.contains("performance"))
                && !NUMBER.matcher(proposed).find()) {
            return false;
        }
        return true;
    }

    private static boolean genericDelta(String original, String proposed, V3UserEvidence item) {
        for (String tech : ConservativeEvidencePacket.extractNormalizedTech(item.displayText())) {
            if (newTech(original, proposed, tech)) {
                return true;
            }
        }
        String surface = item.displayText();
        for (String token : surface.toLowerCase(Locale.ROOT).split("[^a-z0-9/+]+")) {
            if (token.length() < 8) {
                continue;
            }
            if (containsPhrase(proposed, token) && !containsPhrase(original, token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean newlyPresent(String original, String proposed, String evidenceText, String... phrases) {
        for (String phrase : phrases) {
            if (!containsPhrase(evidenceText, phrase) && !isCoreMarker(phrase)) {
                continue;
            }
            if (containsPhrase(proposed, phrase) && !containsPhrase(original, phrase)) {
                return true;
            }
        }
        for (String phrase : phrases) {
            if (isCoreMarker(phrase) && containsPhrase(proposed, phrase) && !containsPhrase(original, phrase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCoreMarker(String phrase) {
        return "nx".equals(phrase) || "kafka".equals(phrase) || "monorepo".equals(phrase)
                || "event-driven".equals(phrase) || "event driven".equals(phrase);
    }

    private static boolean newTech(String original, String proposed, String... techs) {
        for (String tech : techs) {
            if (TermAliasNormalizer.matches(proposed, tech) && !TermAliasNormalizer.matches(original, tech)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNumber(String text, String value, String compact) {
        if (text == null) {
            return false;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String found = matcher.group();
            if (found.equals(value) || found.replace(",", "").equals(compact)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPhrase(String haystack, String phrase) {
        if (haystack == null || phrase == null || haystack.isBlank() || phrase.isBlank()) {
            return false;
        }
        if (TermAliasNormalizer.containsPhrase(haystack, phrase)) {
            return true;
        }
        return TermAliasNormalizer.containsPhrase(
                EvidenceText.normalize(haystack), EvidenceText.normalize(phrase));
    }

    private static boolean containsSkill(List<String> skills, String candidate) {
        if (skills == null) {
            return false;
        }
        for (String skill : skills) {
            if (TermAliasNormalizer.sameTerm(skill, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeMetric(V3UserEvidence item) {
        return item != null && NUMBER.matcher(item.displayText()).find()
                && V3SkillsProjector.extract(item).isEmpty();
    }
}
