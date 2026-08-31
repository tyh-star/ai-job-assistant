package aijob.tailor.v3;

import aijob.util.ActionConceptRegistry;
import aijob.util.EvidenceTopics;
import aijob.util.ObjectDomainRegistry;
import aijob.util.ResumeEntryCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Closed-catalog Enhanced questions. No OpenAI. Not an open synonym system.
 */
final class V3EnhancedQuestionPlanner {

    static final int MAX_PER_ENTRY = 3;
    static final int MAX_TOTAL = 5;

    private static final Pattern MAINTAIN_JD = Pattern.compile("(?i)\\b(?:maintain|maintained|maintaining|maintenance)\\b");
    private static final Pattern TEST_JD = Pattern.compile("(?i)\\b(?:test|testing|tested|junit|unit tests?)\\b");
    private static final Pattern METRIC_JD = Pattern.compile(
            "(?i)\\b(?:performance|scale|throughput|latency|users?|volume)\\b");
    private static final Pattern MAVEN_JD = Pattern.compile("(?i)\\bmaven\\b");
    private static final Pattern SQL_JD = Pattern.compile("(?i)\\b(?:sql|mysql)\\b");
    private static final Pattern DOCKER_K8S = Pattern.compile("(?i)\\b(?:docker|kubernetes|k8s)\\b");
    private static final Pattern COMMUNICATION = Pattern.compile(
            "(?i)\\b(?:communication|teamwork|team player|agile)\\b");
    private static final Pattern JAVA = Pattern.compile("(?i)\\bjava\\b");
    private static final Pattern MAVEN = Pattern.compile("(?i)\\bmaven\\b");
    private static final Pattern SQL = Pattern.compile("(?i)\\b(?:sql|mysql)\\b");
    private static final Pattern CONTAINER = Pattern.compile("(?i)\\b(?:docker|kubernetes|k8s|container)\\b");

    private V3EnhancedQuestionPlanner() {
    }

    static List<Candidate> candidates(V3EntryFactGraph graph, String jobDescription) {
        return candidates(graph, jobDescription, List.of());
    }

    static List<Candidate> candidates(
            V3EntryFactGraph graph,
            String jobDescription,
            List<V3UserEvidence> evidence) {
        List<Candidate> candidates = new ArrayList<>();
        if (graph == null || graph.canonicalEntryKey().isBlank()) {
            return candidates;
        }
        if (!ResumeEntryCatalog.PROJECT.equals(graph.sectionRole())
                && !ResumeEntryCatalog.EXPERIENCE.equals(graph.sectionRole())) {
            return candidates;
        }
        String jd = jobDescription == null ? "" : jobDescription;
        String entry = combined(graph);
        String title = displayTitle(graph.originalHeading());
        String key = graph.canonicalEntryKey();
        boolean backend = ObjectDomainRegistry.contains(entry, ObjectDomainRegistry.BACKEND)
                || JAVA.matcher(entry).find();
        boolean deployed = ActionConceptRegistry.contains(entry, ActionConceptRegistry.DEPLOY)
                || ObjectDomainRegistry.contains(entry, ObjectDomainRegistry.DEPLOYMENT);
        if (MAINTAIN_JD.matcher(jd).find()
                && deployed
                && !ActionConceptRegistry.contains(entry, ActionConceptRegistry.MAINTAIN)
                && !topicSettled(evidence, key, EvidenceTopics.ENHANCED_MAINTAIN)) {
            candidates.add(candidate(key, title, "maintain", 1,
                    "In " + title + ", besides deploying, were you also responsible for later maintenance? If so, describe the actual work you did.",
                    "在「" + title + "」中，除了部署之外，你是否也负责后续维护？如果有，请描述实际做过的工作。"));
        }
        if (internTestingAsked(jd)
                && backend
                && !ActionConceptRegistry.contains(entry, ActionConceptRegistry.TEST)
                && !ObjectDomainRegistry.contains(entry, ObjectDomainRegistry.TESTING)
                && !topicSettled(evidence, key, EvidenceTopics.ENHANCED_TESTING)) {
            candidates.add(candidate(key, title, "testing", 1,
                    "Did this project include automated tests? If yes, which testing tools did you use? You can skip this.",
                    "这个项目是否编写了自动化测试？使用了什么测试工具？没有可以跳过。"));
        }
        if (MAVEN_JD.matcher(jd).find() && JAVA.matcher(entry).find() && !MAVEN.matcher(entry).find()
                && !topicSettled(evidence, key, EvidenceTopics.ENHANCED_BUILD_TOOL)) {
            candidates.add(candidate(key, title, "build-tool", 2,
                    "In " + title + ", which build tool did you use, such as Maven or Gradle? Skip if you did not use one.",
                    "在「" + title + "」中，你使用了什么构建工具（例如 Maven 或 Gradle）？没有可以跳过。"));
        }
        if (SQL_JD.matcher(jd).find()
                && ObjectDomainRegistry.contains(entry, ObjectDomainRegistry.BACKEND)
                && !SQL.matcher(entry).find()
                && !ObjectDomainRegistry.contains(entry, ObjectDomainRegistry.DATABASE)
                && !topicSettled(evidence, key, EvidenceTopics.ENHANCED_DATA_STORE)) {
            candidates.add(candidate(key, title, "data-store", 2,
                    "In " + title + ", which data store or query tool did you use, if any? Skip if this project did not use one.",
                    "在「" + title + "」中，如果用到了数据存储或查询，请说明使用了什么。没有可以跳过。"));
        }
        if (METRIC_JD.matcher(jd).find() && graph.keysOf(V3FactKind.NUMBER).isEmpty()
                && !topicSettled(evidence, key, EvidenceTopics.ENHANCED_METRIC)) {
            candidates.add(candidate(key, title, "metric", 3,
                    "Is there a performance, user-volume, or throughput figure you can confirm for " + title
                            + "? You can skip this if not.",
                    "「" + title + "」是否有可以确认的性能、用户量或处理量数据？没有可以跳过。"));
        }
        return candidates;
    }

    static List<Candidate> skillGapCandidates(
            String jobDescription,
            String skillsText,
            List<V3EntryFactGraph> graphs,
            List<V3UserEvidence> evidence,
            String hostKey,
            List<String> allowedEntryKeys) {
        List<Candidate> candidates = new ArrayList<>();
        if (hostKey == null || hostKey.isBlank() || graphs == null || graphs.isEmpty()) {
            return candidates;
        }
        V3EntryFactGraph host = null;
        for (V3EntryFactGraph graph : graphs) {
            if (hostKey.equals(graph.canonicalEntryKey())) {
                host = graph;
                break;
            }
        }
        if (host == null) {
            return candidates;
        }
        String title = displayTitle(host.originalHeading());
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        for (String requirement : V3EnhancedAtomicCoverage.requirementTexts(jobDescription)) {
            String topic = V3EnhancedAtomicCoverage.skillGapTopic(requirement);
            if (topic.isBlank() || !topics.add(topic)) {
                continue;
            }
            if (topicSettled(evidence, null, topic)) {
                continue;
            }
            V3RequirementCoverage coverage = V3EnhancedAtomicCoverage.coverage(requirement, host, skillsText);
            if (coverage == V3RequirementCoverage.COVERED) {
                continue;
            }
            boolean anyLocal = false;
            for (V3EntryFactGraph graph : graphs) {
                if (V3EnhancedAtomicCoverage.localEvidence(requirement, graph)) {
                    anyLocal = true;
                    break;
                }
            }
            if (coverage == V3RequirementCoverage.PARTIALLY_COVERED && anyLocal
                    && V3EnhancedAtomicCoverage.missingAtoms(requirement, host, skillsText).isEmpty()) {
                continue;
            }
            Candidate question = skillGapQuestion(hostKey, title, topic, requirement, allowedEntryKeys);
            if (question != null) {
                candidates.add(question);
            }
        }
        return candidates;
    }

    static List<V3EnhancedQuestion> select(List<Candidate> all, Set<String> skipped) {
        Set<String> skip = skipped == null ? Set.of() : skipped;
        List<Candidate> ranked = new ArrayList<>();
        for (Candidate candidate : all) {
            if (skip.contains(candidate.question.id())) {
                continue;
            }
            ranked.add(candidate);
        }
        ranked.sort((a, b) -> Integer.compare(a.rank, b.rank));
        List<V3EnhancedQuestion> selected = new ArrayList<>();
        for (Candidate candidate : ranked) {
            int used = 0;
            for (V3EnhancedQuestion chosen : selected) {
                if (chosen.canonicalEntryKey().equals(candidate.question.canonicalEntryKey())) {
                    used++;
                }
            }
            if (used >= MAX_PER_ENTRY || selected.size() >= MAX_TOTAL) {
                continue;
            }
            selected.add(candidate.question);
        }
        return selected;
    }

    static List<String> forbiddenInventions(String jobDescription, String entryText) {
        LinkedHashSet<String> forbidden = new LinkedHashSet<>();
        String jd = jobDescription == null ? "" : jobDescription;
        String entry = entryText == null ? "" : entryText;
        if (DOCKER_K8S.matcher(jd).find() && !CONTAINER.matcher(entry).find()) {
            forbidden.add("Docker / Kubernetes experience");
        }
        if (COMMUNICATION.matcher(jd).find()) {
            forbidden.add("Generic communication, teamwork, or Agile process claims");
        }
        return List.copyOf(forbidden);
    }

    static boolean looksLikeYesNoTrap(String prompt) {
        String lower = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        return lower.contains("do you have docker")
                || lower.contains("you是否具备 docker")
                || Pattern.compile("(?i)^have you (?:used|maintained)\\b").matcher(lower).find()
                        && lower.contains("docker")
                || lower.contains("impressive metric")
                || lower.contains("令人印象深刻");
    }

    static String preferredHost(List<V3EntryFactGraph> graphs) {
        String best = "";
        int bestScore = -1;
        if (graphs == null) {
            return best;
        }
        for (V3EntryFactGraph graph : graphs) {
            if (graph == null || graph.canonicalEntryKey().isBlank()) {
                continue;
            }
            if (!ResumeEntryCatalog.PROJECT.equals(graph.sectionRole())
                    && !ResumeEntryCatalog.EXPERIENCE.equals(graph.sectionRole())) {
                continue;
            }
            int score = hostScore(graph);
            if (score > bestScore) {
                bestScore = score;
                best = graph.canonicalEntryKey();
            }
        }
        return best;
    }

    static List<String> projectEntryKeys(List<V3EntryFactGraph> graphs) {
        List<String> keys = new ArrayList<>();
        if (graphs == null) {
            return keys;
        }
        for (V3EntryFactGraph graph : graphs) {
            if (graph == null || graph.canonicalEntryKey().isBlank()) {
                continue;
            }
            if (ResumeEntryCatalog.PROJECT.equals(graph.sectionRole())
                    || ResumeEntryCatalog.EXPERIENCE.equals(graph.sectionRole())) {
                keys.add(graph.canonicalEntryKey());
            }
        }
        return keys;
    }

    private static int hostScore(V3EntryFactGraph graph) {
        String entry = combined(graph);
        int score = 0;
        if (Pattern.compile("(?i)\\b(?:restful(?:\\s+apis?)?|rest(?:\\s+apis?)?)\\b").matcher(entry).find()) {
            score += 3;
        }
        if (Pattern.compile("(?i)spring\\s*boot").matcher(entry).find()) {
            score += 2;
        }
        if (ObjectDomainRegistry.contains(entry, ObjectDomainRegistry.BACKEND)) {
            score += 2;
        }
        if (JAVA.matcher(entry).find()) {
            score += 1;
        }
        if (ActionConceptRegistry.contains(entry, ActionConceptRegistry.DEPLOY)) {
            score += 1;
        }
        return score;
    }

    private static boolean internTestingAsked(String jd) {
        if (!TEST_JD.matcher(jd).find()) {
            return false;
        }
        return !V3EnhancedAtomicCoverage.nxLikeJob(jd);
    }

    private static boolean topicSettled(List<V3UserEvidence> evidence, String entryKey, String topic) {
        String want = EvidenceTopics.topicKey(topic);
        if (want.isBlank() || evidence == null) {
            return false;
        }
        for (V3UserEvidence item : evidence) {
            if (item == null) {
                continue;
            }
            if (!want.equals(item.topicKey())) {
                continue;
            }
            if (entryKey != null && !entryKey.equals(item.storedEntryKey())) {
                continue;
            }
            if (item.confirmed() || item.rejected()) {
                return true;
            }
        }
        return false;
    }

    private static Candidate skillGapQuestion(
            String hostKey,
            String title,
            String topic,
            String requirement,
            List<String> allowedEntryKeys) {
        String requirementId = V3EnhancedAtomicCoverage.requirementId(requirement);
        String id = "eq-skill-" + requirementId + "-" + topic;
        String promptEn;
        String promptZh;
        if (EvidenceTopics.ENHANCED_API_CONTRACT.equals(topic)) {
            promptEn = "Have you actually designed an API or event contract? If so, say which project and whether it involved request/response structure, versioning, or schema. You can choose \"I have not done this.\"";
            promptZh = "你是否实际设计过 API 或事件契约？如果有，请说明在哪个项目中，以及是否涉及请求/响应结构、版本管理或 schema；没有可以选择“没有做过”。";
        } else if (EvidenceTopics.ENHANCED_NX_MONOREPO.equals(topic)) {
            promptEn = "Have you actually used an Nx monorepo? If so, say which project and whether you used shared libraries, the dependency graph, or affected builds/tests. You can choose \"I have not done this.\"";
            promptZh = "你是否实际使用过 Nx monorepo？如果有，请说明在哪个项目中，以及是否使用过 shared libraries、dependency graph 或 affected builds/tests；没有可以选择“没有做过”。";
        } else if (EvidenceTopics.ENHANCED_EVENT_DRIVEN.equals(topic)) {
            promptEn = "Have you actually worked on an event-driven system or Kafka? If so, say which project and whether you handled retries, idempotency, or schema/version evolution. You can choose \"I have not done this.\"";
            promptZh = "你是否实际参与过 event-driven system 或 Kafka？如果有，请说明在哪个项目中，以及是否处理过重试、幂等或 schema/version evolution；没有可以选择“没有做过”。";
        } else {
            return null;
        }
        V3EnhancedQuestion question = new V3EnhancedQuestion(
                id, hostKey, title, topic, promptEn, promptZh, true,
                requirementId, V3EnhancedAtomicCoverage.cleanRequirement(requirement),
                allowedEntryKeys == null ? List.of(hostKey) : List.copyOf(allowedEntryKeys));
        return new Candidate(1, question);
    }

    private static Candidate candidate(
            String key,
            String title,
            String topic,
            int rank,
            String promptEn,
            String promptZh) {
        String id = "eq-" + key.replace(':', '-').replace('#', '-') + "-" + topic;
        return new Candidate(rank, new V3EnhancedQuestion(id, key, title, topic, promptEn, promptZh, true));
    }

    private static String combined(V3EntryFactGraph graph) {
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

    static String displayTitle(String heading) {
        if (heading == null || heading.isBlank()) {
            return "";
        }
        String[] parts = heading.split("\\|");
        return parts[0] == null ? heading.strip() : parts[0].strip();
    }

    record Candidate(int rank, V3EnhancedQuestion question) {
    }
}
