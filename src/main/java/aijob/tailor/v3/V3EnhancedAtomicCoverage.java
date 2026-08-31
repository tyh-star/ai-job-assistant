package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.util.ActionConceptRegistry;
import aijob.util.EvidenceText;
import aijob.util.ObjectDomainRegistry;
import aijob.util.TermAliasNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Entry-local atomic coverage for the Enhanced Opportunity Map.
 * Does not change {@link V3JdRequirementRanker} used by frozen V3 BASIC.
 * One generic token never covers a whole compound requirement.
 */
public final class V3EnhancedAtomicCoverage {

    public static final String API_CONTRACT = "api-contract";
    public static final String NX_MONOREPO = "nx-monorepo";
    public static final String EVENT_DRIVEN = "event-driven";

    private static final Pattern SPRING_BOOT = Pattern.compile("(?i)spring\\s*boot");
    private static final Pattern REST = Pattern.compile("(?i)\\b(?:restful(?:\\s+apis?)?|rest(?:\\s+apis?)?)\\b");
    private static final Pattern AWS = Pattern.compile("(?i)\\baws\\b");
    private static final Pattern JAVA = Pattern.compile("(?i)\\bjava\\b");
    private static final Pattern MAVEN = Pattern.compile("(?i)\\bmaven\\b");
    private static final Pattern SQL = Pattern.compile("(?i)\\b(?:sql|mysql)\\b");
    private static final Pattern DOCKER = Pattern.compile("(?i)\\bdocker\\b");
    private static final Pattern K8S = Pattern.compile("(?i)\\b(?:kubernetes|k8s)\\b");
    private static final Pattern SPRING = Pattern.compile("(?i)\\bspring\\b");
    private static final Pattern JAVASCRIPT = Pattern.compile("(?i)\\bjavascript\\b|\\bjava\\s+script\\b");
    private static final Pattern NX = Pattern.compile("(?i)(?<!\\p{L})nx(?!\\p{L})");
    private static final Pattern NESTJS = Pattern.compile("(?i)\\bnest\\s*\\.?\\s*js\\b");
    private static final Pattern KAFKA = Pattern.compile("(?i)\\bkafka\\b");
    private static final Pattern KAFKA_EQUIV = Pattern.compile(
            "(?i)\\b(?:rabbitmq|pulsar|kinesis|nats|eventbridge|sns|sqs|pubsub|pub\\s*/?\\s*sub|service\\s+bus)\\b");
    private static final Pattern EVENT_DRIVEN_PHRASE = Pattern.compile("(?i)event\\s*-?\\s*driven");
    private static final Pattern COMMUNICATION = Pattern.compile(
            "(?i)\\b(?:communication|teamwork|team player|agile)\\b");

    private V3EnhancedAtomicCoverage() {
    }

    public static V3RequirementCoverage coverage(String requirement, V3EntryFactGraph graph) {
        return coverage(requirement, graph, "");
    }

    public static V3RequirementCoverage coverage(String requirement, V3EntryFactGraph graph, String skillsText) {
        return evaluate(requirement, graph, skillsText).coverage();
    }

    public static List<Atom> missingAtoms(String requirement, V3EntryFactGraph graph) {
        return missingAtoms(requirement, graph, "");
    }

    public static List<Atom> missingAtoms(String requirement, V3EntryFactGraph graph, String skillsText) {
        return evaluate(requirement, graph, skillsText).missing();
    }

    public static V3JdRequirementBreakdown breakdown(
            String requirement,
            V3EntryFactGraph graph,
            String skillsText) {
        Evaluation evaluation = evaluate(requirement, graph, skillsText);
        String display = evaluation.coverage() == V3RequirementCoverage.COVERED
                ? V3JdRequirementBreakdown.COVERED
                : evaluation.coverage() == V3RequirementCoverage.PARTIALLY_COVERED
                ? V3JdRequirementBreakdown.PARTIALLY_COVERED
                : V3JdRequirementBreakdown.MISSING;
        List<String> covered = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Atom atom : evaluation.atoms()) {
            if (!atom.orGroup().isBlank()) {
                groups.computeIfAbsent(atom.orGroup(), key -> new ArrayList<>()).add(atom.label());
            }
            if (evaluation.presentIds().contains(atom.id())) {
                if (!covered.contains(atom.label())) {
                    covered.add(atom.label());
                }
            } else if (!missing.contains(atom.label())) {
                missing.add(atom.label());
            }
        }
        List<List<String>> alternativeGroups = new ArrayList<>();
        for (List<String> group : groups.values()) {
            if (group.size() > 1) {
                alternativeGroups.add(List.copyOf(group));
            }
        }
        return new V3JdRequirementBreakdown(
                requirementId(requirement),
                cleanRequirement(requirement),
                display,
                covered,
                missing,
                alternativeGroups);
    }

    public static String evidenceText(V3EntryFactGraph graph) {
        if (graph == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(graph.originalEntryText());
        for (V3Fact fact : graph.facts()) {
            if (fact.sourceType() == V3FactSource.USER_CONFIRMED
                    && fact.polarity() == V3FactPolarity.CONFIRMED
                    && !fact.surface().isBlank()) {
                text.append(' ').append(fact.surface());
            }
        }
        return text.toString();
    }

    public static String skillsText(List<ResumeDocumentNode> nodes) {
        V3SkillsSection.Snapshot section = V3SkillsSection.find(nodes);
        StringBuilder text = new StringBuilder();
        for (V3WritableNode node : section.writable()) {
            text.append(' ').append(node.originalText());
        }
        return text.toString();
    }

    public static List<String> requirementTexts(String jobDescription) {
        List<String> parts = new ArrayList<>();
        if (jobDescription == null || jobDescription.isBlank()) {
            return parts;
        }
        for (String raw : jobDescription.split("[\\n\\r\\.!;]+")) {
            String trimmed = cleanRequirement(raw);
            if (trimmed.length() >= 8) {
                parts.add(trimmed);
            }
        }
        if (parts.isEmpty()) {
            parts.add(cleanRequirement(jobDescription));
        }
        return parts;
    }

    public static String requirementId(String requirement) {
        String normalized = EvidenceText.normalize(cleanRequirement(requirement))
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.length() > 56) {
            normalized = normalized.substring(0, 56).replaceAll("-+$", "");
        }
        return normalized.isEmpty() ? "req" : "req-" + normalized;
    }

    public static String cleanRequirement(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.strip().replaceFirst("^[-*•]+\\s*", "").strip();
    }

    public static boolean dockerOrCommunication(String requirement) {
        String sentence = requirement == null ? "" : requirement;
        return DOCKER.matcher(sentence).find()
                || K8S.matcher(sentence).find()
                || COMMUNICATION.matcher(sentence).find();
    }

    public static boolean nxLikeJob(String jobDescription) {
        String jd = jobDescription == null ? "" : jobDescription;
        return NX.matcher(jd).find()
                || containsPhrase(jd, "monorepo")
                || containsPhrase(jd, "affected builds")
                || containsPhrase(jd, "affected tests");
    }

    public static String skillGapTopic(String requirement) {
        List<Atom> atoms = atoms(requirement);
        boolean nx = false;
        boolean event = false;
        boolean contract = false;
        for (Atom atom : atoms) {
            if (atom.id().contains("nx") || atom.id().contains("monorepo")
                    || atom.id().contains("shared-lib") || atom.id().contains("dependency-graph")
                    || atom.id().contains("affected-build")) {
                nx = true;
            }
            if (atom.id().contains("event-driven") || atom.id().contains("kafka")
                    || atom.id().contains("retr") || atom.id().contains("idempoten")
                    || atom.id().contains("schema-evolution")) {
                event = true;
            }
            if (atom.id().contains("api-contract") || atom.id().contains("event-contract")) {
                contract = true;
            }
        }
        if (nx) {
            return NX_MONOREPO;
        }
        if (event) {
            return EVENT_DRIVEN;
        }
        if (contract) {
            return API_CONTRACT;
        }
        return "";
    }

    public static boolean localEvidence(String requirement, V3EntryFactGraph graph) {
        Evaluation evaluation = evaluate(requirement, graph, "");
        return !evaluation.presentIds().isEmpty();
    }

    public static List<Atom> atoms(String requirement) {
        LinkedHashSet<Atom> specific = new LinkedHashSet<>();
        String sentence = requirement == null ? "" : requirement;
        addSpecific(sentence, specific);
        boolean skipGenericActions = !specific.isEmpty();
        LinkedHashSet<Atom> atoms = new LinkedHashSet<>(specific);
        if (JAVA.matcher(sentence).find()) {
            atoms.add(Atom.tech("java", JAVA, "Java", true));
        }
        if (SPRING_BOOT.matcher(sentence).find()) {
            atoms.add(Atom.tech("spring-boot", SPRING_BOOT, "Spring Boot work on this project", true));
        } else if (SPRING.matcher(sentence).find() && !NESTJS.matcher(sentence).find()) {
            atoms.add(Atom.tech("spring-boot", SPRING_BOOT, "Spring Boot work on this project", true));
        }
        if (REST.matcher(sentence).find()) {
            atoms.add(Atom.tech("rest", REST, "REST API work on this project", true));
        }
        if (AWS.matcher(sentence).find()) {
            atoms.add(Atom.tech("aws", AWS, "AWS work on this project", true));
        }
        if (MAVEN.matcher(sentence).find()) {
            atoms.add(Atom.tech("maven", MAVEN, "Build tool used on this project", true));
        }
        if (SQL.matcher(sentence).find()) {
            atoms.add(Atom.tech("sql", SQL, "Data store or query work on this project", true));
        }
        if (DOCKER.matcher(sentence).find()) {
            atoms.add(Atom.tech("docker", DOCKER, "Docker", true));
        }
        if (K8S.matcher(sentence).find()) {
            atoms.add(Atom.tech("kubernetes", K8S, "Kubernetes", true));
        }
        if (!skipGenericActions) {
            for (String family : ActionConceptRegistry.familyIds()) {
                if (ActionConceptRegistry.contains(sentence, family)) {
                    atoms.add(Atom.action(family));
                }
            }
        }
        return new ArrayList<>(atoms);
    }

    private static void addSpecific(String sentence, LinkedHashSet<Atom> atoms) {
        if (NX.matcher(sentence).find()) {
            atoms.add(Atom.tech("nx", NX, "Nx", true));
        }
        if (containsPhrase(sentence, "monorepo") || containsPhrase(sentence, "monorepos")
                || containsPhrase(sentence, "mono-repo")
                || containsPhrase(sentence, "mono repo")) {
            atoms.add(Atom.process("monorepo", "monorepo", "", false,
                    "monorepo", "monorepos", "mono-repo", "mono repo"));
        }
        if (containsPhrase(sentence, "shared libs") || containsPhrase(sentence, "shared libraries")
                || containsPhrase(sentence, "shared lib")) {
            atoms.add(Atom.process("shared-libs", "shared libraries", "", false,
                    "shared libraries", "shared libs", "shared lib"));
        }
        if (containsPhrase(sentence, "dependency graph") || containsPhrase(sentence, "dependency graphs")) {
            atoms.add(Atom.process("dependency-graph", "dependency graph", "", false,
                    "dependency graph", "dependency graphs"));
        }
        if (containsPhrase(sentence, "affected builds") || containsPhrase(sentence, "affected tests")
                || containsPhrase(sentence, "affected builds/tests")) {
            atoms.add(Atom.process("affected-builds", "affected builds/tests", "", false,
                    "affected builds/tests", "affected builds", "affected tests"));
        }
        if (EVENT_DRIVEN_PHRASE.matcher(sentence).find()) {
            atoms.add(Atom.process("event-driven", "event-driven system", "", false,
                    "event-driven", "event driven"));
        }
        if (KAFKA.matcher(sentence).find() || containsPhrase(sentence, "or equivalent")
                && EVENT_DRIVEN_PHRASE.matcher(sentence).find()) {
            atoms.add(Atom.tech("kafka", KAFKA, "Kafka", true).withOrGroup("kafka"));
            atoms.add(Atom.equivalent("kafka-equivalent", "confirmed Kafka equivalent", "kafka"));
        }
        if (containsPhrase(sentence, "retries") || containsPhrase(sentence, "retry")) {
            atoms.add(Atom.process("retries", "retries", "", false, "retries", "retry"));
        }
        if (containsPhrase(sentence, "idempotency") || containsPhrase(sentence, "idempotent")) {
            atoms.add(Atom.process("idempotency", "idempotency", "", false, "idempotency", "idempotent"));
        }
        if (containsPhrase(sentence, "schema/version") || containsPhrase(sentence, "schema evolution")
                || containsPhrase(sentence, "version evolution") || containsPhrase(sentence, "schema version")) {
            atoms.add(Atom.process("schema-evolution", "schema/version evolution", "", false,
                    "schema/version evolution", "schema evolution", "version evolution", "schema/version",
                    "schema version"));
        }
        boolean apiEventContract = containsPhrase(sentence, "api/event contract")
                || containsPhrase(sentence, "api contract")
                || containsPhrase(sentence, "event contract")
                || containsPhrase(sentence, "contract design")
                && (containsPhrase(sentence, "api") || containsPhrase(sentence, "event"));
        if (apiEventContract) {
            atoms.add(Atom.process("api-contract", "API contract design", "contract", false,
                    "api/event contract", "api contract", "api contract design", "contract design"));
            atoms.add(Atom.process("event-contract", "event contract design", "contract", false,
                    "event contract", "event contract design", "api/event contract"));
        }
        if (NESTJS.matcher(sentence).find()) {
            atoms.add(Atom.tech("nestjs", NESTJS, "NestJS", true));
        }
        if (JAVASCRIPT.matcher(sentence).find()) {
            atoms.add(Atom.tech("javascript", JAVASCRIPT, "JavaScript", true));
        }
    }

    private static Evaluation evaluate(String requirement, V3EntryFactGraph graph, String skillsText) {
        String evidence = evidenceText(graph);
        List<Atom> atoms = atoms(requirement);
        if (atoms.isEmpty()) {
            return new Evaluation(List.of(), Set.of(), List.of(), V3RequirementCoverage.LOW_VALUE);
        }
        LinkedHashSet<String> presentIds = new LinkedHashSet<>();
        List<Atom> missing = new ArrayList<>();
        Map<String, Boolean> orSatisfied = new LinkedHashMap<>();
        Map<String, Boolean> requiredMissing = new LinkedHashMap<>();
        for (Atom atom : atoms) {
            boolean present = atom.presentIn(evidence, graph, skillsText);
            if (present) {
                presentIds.add(atom.id());
            } else {
                missing.add(atom);
            }
            if (atom.orGroup().isBlank()) {
                if (!present) {
                    requiredMissing.put(atom.id(), true);
                }
            } else {
                orSatisfied.merge(atom.orGroup(), present, Boolean::logicalOr);
            }
        }
        boolean allRequired = requiredMissing.isEmpty();
        boolean allOr = true;
        for (Boolean ok : orSatisfied.values()) {
            if (!Boolean.TRUE.equals(ok)) {
                allOr = false;
                break;
            }
        }
        V3RequirementCoverage coverage;
        if (allRequired && allOr) {
            coverage = V3RequirementCoverage.COVERED;
        } else if (!presentIds.isEmpty()) {
            coverage = V3RequirementCoverage.PARTIALLY_COVERED;
        } else {
            coverage = V3RequirementCoverage.UNSUPPORTED;
        }
        return new Evaluation(atoms, presentIds, missing, coverage);
    }

    private static boolean containsPhrase(String haystack, String phrase) {
        if (haystack == null || phrase == null || haystack.isBlank() || phrase.isBlank()) {
            return false;
        }
        if (TermAliasNormalizer.containsPhrase(haystack, phrase)) {
            return true;
        }
        return TermAliasNormalizer.containsPhrase(EvidenceText.normalize(haystack), EvidenceText.normalize(phrase));
    }

    public record Atom(
            String id,
            String kind,
            String actionFamily,
            Pattern tech,
            String label,
            boolean possessable,
            String orGroup,
            List<String> phrases
    ) {
        public Atom {
            id = id == null ? "" : id;
            kind = kind == null ? "" : kind;
            actionFamily = actionFamily == null ? "" : actionFamily;
            label = label == null || label.isBlank() ? id : label;
            orGroup = orGroup == null ? "" : orGroup;
            phrases = phrases == null ? List.of() : List.copyOf(phrases);
        }

        static Atom action(String family) {
            return new Atom("action:" + family, "action", family, null, actionLabel(family), false, "", List.of());
        }

        static Atom tech(String atomId, Pattern pattern, String label, boolean possessable) {
            return new Atom("tech:" + atomId, "tech", "", pattern, label, possessable, "", List.of());
        }

        static Atom process(String atomId, String label, String orGroup, boolean possessable, String... phrases) {
            return new Atom("process:" + atomId, "process", "", null, label, possessable,
                    orGroup == null ? "" : orGroup, List.of(phrases));
        }

        static Atom equivalent(String atomId, String label, String orGroup) {
            return new Atom("tech:" + atomId, "equivalent", "", KAFKA_EQUIV, label, false, orGroup, List.of());
        }

        Atom withOrGroup(String group) {
            return new Atom(id, kind, actionFamily, tech, label, possessable, group, phrases);
        }

        boolean presentIn(String evidence, V3EntryFactGraph graph) {
            return presentIn(evidence, graph, "");
        }

        boolean presentIn(String evidence, V3EntryFactGraph graph, String skillsText) {
            String entry = evidence == null ? "" : evidence;
            String skills = skillsText == null ? "" : skillsText;
            if (possessable && matches(skills)) {
                return true;
            }
            if ("action".equals(kind)) {
                if (ActionConceptRegistry.contains(entry, actionFamily)) {
                    return true;
                }
                return graph != null && graph.facts().stream().anyMatch(fact ->
                        fact.polarity() == V3FactPolarity.CONFIRMED
                                && fact.kind() == V3FactKind.ACTION
                                && fact.normalizedFact().equals("action:" + actionFamily));
            }
            if ("equivalent".equals(kind)) {
                return confirmedEquivalent(graph);
            }
            return matches(entry);
        }

        private boolean matches(String text) {
            if (text == null || text.isBlank()) {
                return false;
            }
            if (tech != null && tech.matcher(text).find()) {
                return true;
            }
            for (String phrase : phrases) {
                if (containsPhrase(text, phrase)) {
                    return true;
                }
            }
            if ("tech:aws".equals(id) && ObjectDomainRegistry.contains(text, ObjectDomainRegistry.DEPLOYMENT)
                    && AWS.matcher(text).find()) {
                return true;
            }
            return false;
        }

        private boolean confirmedEquivalent(V3EntryFactGraph graph) {
            if (graph == null) {
                return false;
            }
            return graph.facts().stream().anyMatch(fact ->
                    fact.sourceType() == V3FactSource.USER_CONFIRMED
                            && fact.polarity() == V3FactPolarity.CONFIRMED
                            && KAFKA_EQUIV.matcher(fact.surface() == null ? "" : fact.surface()).find());
        }

        private static String actionLabel(String family) {
            if (ActionConceptRegistry.MAINTAIN.equals(family)) {
                return "Later maintenance work after deployment";
            }
            if (ActionConceptRegistry.TEST.equals(family)) {
                return "Automated tests and tools actually used";
            }
            return family;
        }
    }

    private record Evaluation(
            List<Atom> atoms,
            Set<String> presentIds,
            List<Atom> missing,
            V3RequirementCoverage coverage
    ) {
        private Evaluation {
            atoms = atoms == null ? List.of() : List.copyOf(atoms);
            presentIds = presentIds == null ? Set.of() : Set.copyOf(presentIds);
            missing = missing == null ? List.of() : List.copyOf(missing);
            coverage = coverage == null ? V3RequirementCoverage.UNSUPPORTED : coverage;
        }
    }
}
