package aijob.tailor.v3;

import aijob.util.ConservativeEvidencePacket;
import aijob.util.EvidenceText;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V3 entry-level validator. The unit is the final entry text, not a pair of
 * sentences. Same-entry fact moves and professional wording are allowed.
 */
public final class V3EntryRewriteValidator {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern PRESENT = Pattern.compile(
            "\\b(?:present|current)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)\\b(?:bachelor|master|phd|doctorate|gpa|certified|certification|"
                    + "university|college|degree)\\b");

    private V3EntryRewriteValidator() {
    }

    public static V3ValidationDecision validate(
            V3ResumeFactGraphs resume,
            V3EntryRewrite rewrite) {

        if (resume == null || rewrite == null || rewrite.canonicalEntryKey().isBlank()) {
            return V3ValidationDecision.reject(V3ValidationDecision.IDENTITY_MUTATION, "missing entry");
        }
        V3EntryFactGraph target = resume.entry(rewrite.canonicalEntryKey());
        if (target == null) {
            return V3ValidationDecision.reject(V3ValidationDecision.IDENTITY_MUTATION, "unknown entry key");
        }
        String original = target.originalEntryText();
        String rewritten = fullRewritten(target, rewrite);

        String identity = identityMutation(target, rewrite);
        if (identity != null) {
            return V3ValidationDecision.reject(V3ValidationDecision.IDENTITY_MUTATION, identity);
        }
        V3Fact rejected = rejectedFactUsed(target, rewritten);
        if (rejected != null) {
            return V3ValidationDecision.reject(V3ValidationDecision.USER_REJECTED_FACT, rejected.surface());
        }
        String cross = crossEntryFact(resume, target, rewritten);
        if (cross != null) {
            return V3ValidationDecision.reject(V3ValidationDecision.CROSS_ENTRY_FACT, cross);
        }
        String invented = inventedSkill(target, rewritten);
        if (invented != null) {
            return V3ValidationDecision.reject(V3ValidationDecision.INVENTED_SKILL, invented);
        }
        String credential = inventedCredential(target, rewritten);
        if (credential != null) {
            return V3ValidationDecision.reject(V3ValidationDecision.INVENTED_CREDENTIAL, credential);
        }
        String metric = unconfirmedMetric(target, rewritten);
        if (metric != null) {
            return V3ValidationDecision.reject(V3ValidationDecision.UNCONFIRMED_METRIC, metric);
        }
        String missing = missingMaterialFact(target, rewritten);
        if (missing != null) {
            return V3ValidationDecision.reject(V3ValidationDecision.MISSING_MATERIAL_FACT, missing);
        }
        String duplicate = duplicateMaterialFact(target, rewrite.rewrittenBullets());
        if (duplicate != null) {
            return V3ValidationDecision.reject(V3ValidationDecision.DUPLICATE_MATERIAL_FACT, duplicate);
        }
        V3ValidationDecision provenance = V3ActionObjectProvenanceGate.check(target, rewrite.rewrittenBullets());
        if (!provenance.accepted()) {
            return provenance;
        }
        if (V3EntryQualityEvaluator.hasRoleEscalation(
                String.join(" ", target.originalBulletTexts()),
                rewritten)) {
            return V3ValidationDecision.reject(V3ValidationDecision.ROLE_ESCALATION, "leadership without evidence");
        }
        return V3ValidationDecision.accept();
    }

    private static String identityMutation(V3EntryFactGraph target, V3EntryRewrite rewrite) {
        String heading = rewrite.rewrittenHeading().isBlank()
                ? target.originalHeading()
                : rewrite.rewrittenHeading();
        String dates = rewrite.rewrittenDateText().isBlank()
                ? target.originalDateText()
                : rewrite.rewrittenDateText();
        if (!EvidenceText.normalize(target.originalHeading()).equals(EvidenceText.normalize(heading))) {
            return "heading changed";
        }
        if (!EvidenceText.normalize(target.originalDateText()).equals(EvidenceText.normalize(dates))) {
            return "dates or employment status changed";
        }
        boolean presentChanged = PRESENT.matcher(target.originalHeading() + " " + target.originalDateText()).find()
                != PRESENT.matcher(heading + " " + dates).find();
        if (presentChanged) {
            return "employment status changed";
        }
        return null;
    }

    private static V3Fact rejectedFactUsed(V3EntryFactGraph target, String rewritten) {
        for (V3Fact fact : target.factsOf(V3FactKind.USER_REJECTED)) {
            if (fact.coveredBy(rewritten)) {
                return fact;
            }
        }
        return null;
    }

    private static String crossEntryFact(
            V3ResumeFactGraphs resume,
            V3EntryFactGraph target,
            String rewritten) {

        Set<String> mine = target.keysOf(V3FactKind.TECHNOLOGY);
        for (V3EntryFactGraph other : resume.entries()) {
            if (other.canonicalEntryKey().equals(target.canonicalEntryKey())) {
                continue;
            }
            for (V3Fact fact : other.factsOf(V3FactKind.TECHNOLOGY)) {
                if (mine.contains(fact.factKey())) {
                    continue;
                }
                if (fact.coveredBy(rewritten) && !fact.coveredBy(target.originalEntryText())) {
                    return fact.surface();
                }
            }
        }
        return null;
    }

    private static String inventedSkill(V3EntryFactGraph target, String rewritten) {
        String original = target.originalEntryText();
        for (String tech : ConservativeEvidencePacket.extractNormalizedTech(rewritten)) {
            if (!V3FactCoverageResolver.textAllowsTech(original, target, tech)) {
                return tech;
            }
        }
        return null;
    }

    private static String inventedCredential(V3EntryFactGraph target, String rewritten) {
        Set<String> original = credentials(target.originalEntryText());
        Set<String> next = credentials(rewritten);
        next.removeAll(original);
        if (!next.isEmpty()) {
            return next.iterator().next();
        }
        return null;
    }

    private static String unconfirmedMetric(V3EntryFactGraph target, String rewritten) {
        Set<String> allowed = target.keysOf(V3FactKind.NUMBER);
        for (V3Fact fact : target.factsOf(V3FactKind.NUMBER)) {
            allowed.add(fact.surface());
        }
        allowed.addAll(numbers(target.originalEntryText()));
        Set<String> originalYears = years(target.originalEntryText());
        for (String number : numbers(rewritten)) {
            if (originalYears.contains(number)) {
                continue;
            }
            if (!allowed.contains(number) && !allowed.contains("number:" + number)) {
                return number;
            }
        }
        return null;
    }

    private static String missingMaterialFact(V3EntryFactGraph target, String rewritten) {
        String original = fullOriginal(target);
        for (V3Fact fact : target.facts()) {
            if (!fact.protectedFact()) {
                continue;
            }
            if (fact.kind() == V3FactKind.IDENTITY || fact.kind() == V3FactKind.NUMBER) {
                if (!fact.coveredBy(original)) {
                    continue;
                }
            }
            if (fact.coveredBy(original) && !fact.coveredBy(rewritten)) {
                return fact.surface().isBlank() ? fact.factKey() : fact.surface();
            }
        }
        if (hasLead(original) && !hasLead(rewritten)) {
            return "lead";
        }
        return null;
    }

    private static String fullOriginal(V3EntryFactGraph target) {
        return target.originalEntryText();
    }

    private static String fullRewritten(V3EntryFactGraph target, V3EntryRewrite rewrite) {
        String heading = rewrite.rewrittenHeading().isBlank()
                ? target.originalHeading()
                : rewrite.rewrittenHeading();
        String dates = rewrite.rewrittenDateText().isBlank()
                ? target.originalDateText()
                : rewrite.rewrittenDateText();
        StringBuilder text = new StringBuilder(heading);
        if (!dates.isBlank()) {
            text.append(' ').append(dates);
        }
        for (String bullet : rewrite.rewrittenBullets()) {
            text.append(' ').append(bullet);
        }
        return text.toString();
    }

    private static boolean hasLead(String text) {
        return text != null && java.util.regex.Pattern.compile("(?i)\\b(?:lead|led)\\b").matcher(text).find();
    }

    private static String duplicateMaterialFact(V3EntryFactGraph target, List<String> bullets) {
        if (bullets == null || bullets.size() < 2) {
            return null;
        }
        for (V3Fact fact : target.factsOf(V3FactKind.TECHNOLOGY)) {
            if (!fact.protectedFact()) {
                continue;
            }
            int hits = 0;
            for (String bullet : bullets) {
                if (fact.coveredBy(bullet)) {
                    hits++;
                }
            }
            int originalHits = 0;
            for (String bullet : target.originalBulletTexts()) {
                if (fact.coveredBy(bullet)) {
                    originalHits++;
                }
            }
            if (hits > 1 && hits > originalHits) {
                return fact.surface().isBlank() ? fact.factKey() : fact.surface();
            }
        }
        return null;
    }

    private static Set<String> numbers(String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            values.add(matcher.group().replace(",", ""));
        }
        return values;
    }

    private static Set<String> years(String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = YEAR.matcher(text == null ? "" : text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private static Set<String> credentials(String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = CREDENTIAL.matcher(text == null ? "" : text);
        while (matcher.find()) {
            values.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return values;
    }
}
