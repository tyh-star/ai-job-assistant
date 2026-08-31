package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.util.ActionConceptRegistry;
import aijob.util.CanonicalEntryKeyResolver;
import aijob.util.ClaimLedger;
import aijob.util.ConservativeEvidencePacket;
import aijob.util.ConservativeMaterialFacts;
import aijob.util.ObjectDomainRegistry;
import aijob.util.ResumeEntryCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives an immutable entry-level Fact Graph from ClaimLedger grouping plus
 * the evidence registry. Does not mutate ClaimLedger or evidence items.
 */
public final class V3EntryFactGraphFactory {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern PRESENT = Pattern.compile(
            "\\b(?:present|current)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH = Pattern.compile(
            "\\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
                    + "jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|"
                    + "nov(?:ember)?|dec(?:ember)?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)\\b(?:bachelor|master|phd|doctorate|gpa|certified|certification|"
                    + "university|college|degree)\\b");
    private static final Pattern LEAD = Pattern.compile("(?i)\\b(?:lead|led)\\b");

    private V3EntryFactGraphFactory() {
    }

    public static V3ResumeFactGraphs from(ClaimLedger ledger) {
        return from(ledger, List.of());
    }

    public static V3ResumeFactGraphs from(ClaimLedger ledger, List<V3UserEvidence> evidence) {
        List<V3UserEvidence> items = evidence == null ? List.of() : evidence;
        if (ledger == null) {
            return new V3ResumeFactGraphs(List.of(), unlinked(items, Set.of()));
        }
        CanonicalEntryKeyResolver.Index index = CanonicalEntryKeyResolver.index(ledger);
        Set<String> knownKeys = new LinkedHashSet<>(index.entryByKey().keySet());
        List<V3EntryFactGraph> graphs = new ArrayList<>();
        for (ClaimLedger.Entry entry : ledger.entries()) {
            String key = index.keyForEntryId(entry.entryId());
            graphs.add(buildEntry(entry, key, items));
        }
        return new V3ResumeFactGraphs(graphs, unlinked(items, knownKeys));
    }

    private static V3EntryFactGraph buildEntry(
            ClaimLedger.Entry entry,
            String canonicalKey,
            List<V3UserEvidence> evidence) {

        List<String> nodeIds = entry.sourceNodeIds() == null ? List.of() : entry.sourceNodeIds();
        String heading = "";
        String dates = "";
        List<String> bullets = new ArrayList<>();
        int i = 0;
        for (ResumeDocumentNode node : entry.nodes()) {
            String text = node.getText() == null ? "" : node.getText();
            if (i == 0) {
                heading = text;
            } else if (looksLikeUrl(text)) {
                i++;
                continue;
            } else if (looksLikeDateLine(text) && bullets.isEmpty()) {
                dates = dates.isBlank() ? text : dates + " " + text;
            } else {
                bullets.add(text);
            }
            i++;
        }
        List<V3Fact> facts = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        LinkedHashSet<String> body = new LinkedHashSet<>(bullets);
        for (ResumeDocumentNode node : entry.nodes()) {
            String text = node.getText() == null ? "" : node.getText();
            if (body.contains(text) && !looksLikeUrl(text)) {
                addResumeFacts(facts, seen, canonicalKey, node);
            }
        }
        addIdentityFacts(facts, seen, canonicalKey, heading + " " + dates, nodeIds);
        for (V3UserEvidence item : evidence) {
            if (item == null || !canonicalKey.equals(item.storedEntryKey())) {
                continue;
            }
            addUserFacts(facts, seen, canonicalKey, item, nodeIds);
        }
        return new V3EntryFactGraph(
                canonicalKey,
                entry.entryId(),
                entry.sectionRole() == null ? "" : entry.sectionRole(),
                nodeIds,
                bullets,
                heading,
                dates,
                V3FactMaterialityClassifier.classify(facts)
        );
    }

    private static void addResumeFacts(
            List<V3Fact> facts,
            Set<String> seen,
            String entryKey,
            ResumeDocumentNode node) {

        String text = node == null ? "" : node.getText();
        String nodeId = node == null ? "" : node.getNodeId();
        List<String> sources = nodeId == null || nodeId.isBlank() ? List.of() : List.of(nodeId);
        if (LEAD.matcher(text == null ? "" : text).find()) {
            add(facts, seen, resumeFact(
                    entryKey,
                    V3FactKind.ACTION,
                    "action:lead",
                    "lead",
                    List.of("lead", "led"),
                    sources));
        }
        for (String action : ActionConceptRegistry.actionsIn(text)) {
            add(facts, seen, resumeFact(entryKey, V3FactKind.ACTION, "action:" + action, action, List.of(action), sources));
        }
        for (String tech : ConservativeEvidencePacket.extractNormalizedTech(text)) {
            add(facts, seen, resumeFact(
                    entryKey,
                    V3FactKind.TECHNOLOGY,
                    "tech:" + tech.toLowerCase(Locale.ROOT),
                    tech,
                    List.of(tech.toLowerCase(Locale.ROOT)),
                    sources));
        }
        for (ConservativeMaterialFacts.Fact material : ConservativeMaterialFacts.extract(text)) {
            V3FactKind kind = switch (material.category()) {
                case TASK -> V3FactKind.TASK;
                case OBJECT -> V3FactKind.OBJECT;
                case CONSTRAINT -> V3FactKind.CONSTRAINT;
                case RESULT -> V3FactKind.OUTCOME;
                case TECH, ROLE, USER_CONTEXT -> null;
            };
            if (kind == null) {
                continue;
            }
            add(facts, seen, resumeFact(entryKey, kind, material.key(), material.key(), material.aliases(), sources));
        }
        for (String domain : ObjectDomainRegistry.domainsIn(text)) {
            add(facts, seen, resumeFact(
                    entryKey,
                    V3FactKind.OBJECT,
                    "domain:" + domain.toLowerCase(Locale.ROOT),
                    domain,
                    List.of(domain.toLowerCase(Locale.ROOT)),
                    sources));
        }
        Matcher numbers = NUMBER.matcher(text == null ? "" : text);
        while (numbers.find()) {
            String value = numbers.group().replace(",", "");
            add(facts, seen, resumeFact(entryKey, V3FactKind.NUMBER, "number:" + value, value, List.of(value), sources));
        }
    }

    private static void addIdentityFacts(
            List<V3Fact> facts,
            Set<String> seen,
            String entryKey,
            String headingAndDates,
            List<String> sourceNodeIds) {

        String text = headingAndDates == null ? "" : headingAndDates;
        Matcher years = YEAR.matcher(text);
        while (years.find()) {
            add(facts, seen, resumeFact(
                    entryKey,
                    V3FactKind.IDENTITY,
                    "identity:year:" + years.group(),
                    years.group(),
                    List.of(years.group()),
                    sourceNodeIds));
        }
        if (PRESENT.matcher(text).find()) {
            add(facts, seen, resumeFact(
                    entryKey,
                    V3FactKind.IDENTITY,
                    "identity:status:present",
                    "present",
                    List.of("present", "current"),
                    sourceNodeIds));
        }
        Matcher creds = CREDENTIAL.matcher(text);
        while (creds.find()) {
            String token = creds.group().toLowerCase(Locale.ROOT);
            add(facts, seen, resumeFact(
                    entryKey,
                    V3FactKind.IDENTITY,
                    "identity:credential:" + token,
                    token,
                    List.of(token),
                    sourceNodeIds));
        }
    }

    private static void addUserFacts(
            List<V3Fact> facts,
            Set<String> seen,
            String entryKey,
            V3UserEvidence item,
            List<String> sourceNodeIds) {

        if (!item.confirmed() && !item.rejected()) {
            return;
        }
        V3FactPolarity polarity = item.confirmed() ? V3FactPolarity.CONFIRMED : V3FactPolarity.REJECTED;
        V3FactKind kind = item.confirmed() ? V3FactKind.USER_CONFIRMED : V3FactKind.USER_REJECTED;
        String prefix = item.confirmed() ? "user-confirmed:" : "user-rejected:";
        String surface = item.combinedText();
        if (surface.isBlank()) {
            return;
        }
        List<String> aliases = new ArrayList<>();
        if (!item.metricText().isBlank()) {
            aliases.add(item.metricText());
            Matcher numbers = NUMBER.matcher(item.metricText());
            while (numbers.find()) {
                aliases.add(numbers.group().replace(",", ""));
            }
        }
        if (!item.resultText().isBlank()) {
            aliases.add(item.resultText());
        }
        if (!item.actionText().isBlank()) {
            aliases.add(item.actionText());
        }
        if (!item.topicLabel().isBlank()) {
            aliases.add(item.topicLabel());
        }
        add(facts, seen, userFact(
                entryKey,
                kind,
                prefix + (item.evidenceItemId() == null ? surface.toLowerCase(Locale.ROOT) : item.evidenceItemId()),
                surface,
                aliases,
                sourceNodeIds,
                polarity,
                item.evidenceItemId()));
        if (item.confirmed()) {
            Matcher numbers = NUMBER.matcher(surface);
            while (numbers.find()) {
                String value = numbers.group().replace(",", "");
                add(facts, seen, userFact(
                        entryKey,
                        V3FactKind.NUMBER,
                        "number:" + value,
                        value,
                        List.of(value),
                        sourceNodeIds,
                        V3FactPolarity.CONFIRMED,
                        item.evidenceItemId()));
            }
            for (String tech : ConservativeEvidencePacket.extractNormalizedTech(surface)) {
                add(facts, seen, userFact(
                        entryKey,
                        V3FactKind.TECHNOLOGY,
                        "tech:" + tech.toLowerCase(Locale.ROOT),
                        tech,
                        List.of(tech.toLowerCase(Locale.ROOT)),
                        sourceNodeIds,
                        V3FactPolarity.CONFIRMED,
                        item.evidenceItemId()));
            }
        }
    }

    private static V3Fact resumeFact(
            String entryKey,
            V3FactKind kind,
            String normalized,
            String surface,
            List<String> aliases,
            List<String> sources) {
        return new V3Fact(
                entryKey,
                kind,
                normalized,
                surface,
                aliases,
                sources,
                V3FactSource.RESUME,
                V3FactPolarity.CONFIRMED,
                null);
    }

    private static V3Fact userFact(
            String entryKey,
            V3FactKind kind,
            String normalized,
            String surface,
            List<String> aliases,
            List<String> sources,
            V3FactPolarity polarity,
            Long evidenceItemId) {
        return new V3Fact(
                entryKey,
                kind,
                normalized,
                surface,
                aliases,
                sources,
                V3FactSource.USER_CONFIRMED,
                polarity,
                evidenceItemId);
    }

    private static List<V3UserEvidence> unlinked(List<V3UserEvidence> items, Set<String> knownKeys) {
        List<V3UserEvidence> leftover = new ArrayList<>();
        for (V3UserEvidence item : items) {
            if (item == null) {
                continue;
            }
            if (item.storedEntryKey().isBlank()
                    || ResumeEntryCatalog.GENERAL.equals(item.storedEntryKey())
                    || !knownKeys.contains(item.storedEntryKey())) {
                leftover.add(item);
            }
        }
        return leftover;
    }

    private static void add(List<V3Fact> facts, Set<String> seen, V3Fact fact) {
        if (fact == null || fact.normalizedFact().isBlank() || !seen.add(fact.kind() + "|" + fact.normalizedFact())) {
            return;
        }
        facts.add(fact);
    }

    private static boolean looksLikeDateLine(String text) {
        if (text == null || text.isBlank() || text.length() > 80) {
            return false;
        }
        return YEAR.matcher(text).find() || MONTH.matcher(text).find() || PRESENT.matcher(text).find();
    }

    private static boolean looksLikeUrl(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("http");
    }
}
