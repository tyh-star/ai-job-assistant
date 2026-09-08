package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.dto.V3EntryReview;
import aijob.dto.V3EvidenceUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3EnhancedFinalAttributionTest {

    private static final String PRIME = "PROJECT:prime-deals";
    private static final String SMART = "PROJECT:smartpc-builder";
    private static final String JD = V3EnhancedAtomicJdCoverageTest.JD_VIVA;
    private static final String SKILLS =
            "● Programming Languages: C/C++, Java, Python, Shell Script, SQL, HTML, Vue, Javascript, CSS, IntelliJ, Eclipse, PyCharm, GitHub, DBeaver, VScode, MySQL, Windows, Unix/Linux, Mac OS";
    private static final String NX_TEXT =
            "Nx monorepo with Vue/TypeScript shared libraries, dependency graph, and affected builds/tests";
    private static final String API_TEXT =
            "REST API contracts including JSON request/response, validation/error responses, and /api/v1 versioning";
    private static final String KAFKA_TEXT =
            "Kafka event processing with bounded retries, a dead-letter topic, idempotent consumers, and event schema versioning";
    private static final String PRIME_METRIC = "Prime checkout handled 1,200 orders per day";
    private static final String SMART_METRIC = "Processed 10,000 combinations in under 500 ms";

    @Test
    void promptOnlyEvidenceIsNotUsedInEntry() {
        String original = originalPrime();
        String proposed = grammarPrime();
        assertFalse(V3FinalEvidenceAttributor.usedInEntry(nx(), original, proposed));
        assertFalse(V3FinalEvidenceAttributor.usedInEntry(api(), original, proposed));
        assertFalse(V3FinalEvidenceAttributor.usedInEntry(kafka(), original, proposed));
    }

    @Test
    void restAloneDoesNotUseApiContractEvidence() {
        String original = originalPrime();
        String proposed = original + " Continued to integrate REST APIs for authentication.";
        assertFalse(V3FinalEvidenceAttributor.usedInEntry(api(), original, proposed));
    }

    @Test
    void jsonVersionAndErrorContractUsesApiEvidence() {
        assertTrue(V3FinalEvidenceAttributor.usedInEntry(api(), originalPrime(), fullPrime()));
    }

    @Test
    void nxFactsUseNxEvidence() {
        assertTrue(V3FinalEvidenceAttributor.usedInEntry(nx(), originalPrime(), fullPrime()));
    }

    @Test
    void kafkaRetryIdempotencySchemaUsesKafkaEvidence() {
        assertTrue(V3FinalEvidenceAttributor.usedInEntry(kafka(), originalPrime(), fullPrime()));
    }

    @Test
    void metricNumbersMissingAreNotUsed() {
        assertFalse(V3FinalEvidenceAttributor.usedInEntry(primeMetric(), originalPrime(), fullPrime()));
        assertFalse(V3FinalEvidenceAttributor.usedInEntry(smartMetric(), originalPrime(), fullPrime()));
    }

    @Test
    void missingRequiredEvidenceRepairsOnceThenAdmits() {
        CountingGenerator generator = new CountingGenerator(true);
        V3EnhancedRunResult result = run(generator);
        assertEquals(1, generator.repairs.get());
        assertEquals(1, result.repairCalls());
        V3EntryReview prime = primeReview(result);
        assertEquals("EVIDENCE_ENHANCED", prime.getSuggestionType());
        assertTrue(prime.getUsedEvidenceItemIds().containsAll(List.of(61L, 62L, 63L)));
        assertFalse(prime.getUsedEvidenceItemIds().contains(64L));
        assertEquals(
                "Incorporates your confirmed Nx, API contract, and Kafka experience to better match the role’s key requirements.",
                prime.getWhyBetter());
        assertEquals(
                "结合了你确认的 Nx、API 契约和 Kafka 经历，使项目与目标职位的关键要求更加匹配。",
                prime.getWhyBetterZh());
        assertFalse(prime.getWhyBetter().toLowerCase(Locale.ROOT).contains("event-driven"));
        assertEquals(V3EvidenceUsage.USED_IN_ENTRY_AND_SKILLS, usage(result, 61L).getUsageStatus());
        assertEquals(V3EvidenceUsage.USED_IN_ENTRY_AND_SKILLS, usage(result, 62L).getUsageStatus());
        assertEquals(V3EvidenceUsage.USED_IN_ENTRY_AND_SKILLS, usage(result, 63L).getUsageStatus());
        assertEquals(V3EvidenceUsage.SAVED_NOT_USED, usage(result, 64L).getUsageStatus());
        assertEquals(V3EvidenceUsage.SAVED_NOT_USED, usage(result, 65L).getUsageStatus());
        assertEquals(0, result.writerCalls());
        String proposed = prime.getProposedNodes().stream().map(node -> node.getText()).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(proposed.toLowerCase(Locale.ROOT).contains("nx"));
        assertTrue(proposed.toLowerCase(Locale.ROOT).contains("kafka"));
        assertTrue(proposed.toLowerCase(Locale.ROOT).contains("json"));
        assertFalse(proposed.toLowerCase(Locale.ROOT).contains("nestjs"));
        assertFalse(proposed.toLowerCase(Locale.ROOT).contains("smartpc"));
        assertFalse(proposed.contains("1,200"));
        String skills = result.skillReviews().get(0).getProposedSkills();
        assertTrue(skills.startsWith(SKILLS));
        assertEquals(SKILLS, skills.substring(0, SKILLS.length()));
        assertTrue(skills.endsWith("Kafka, REST APIs, TypeScript, Nx"));
        assertTrue(skills.contains("C/C++"));
        assertTrue(skills.contains("Unix/Linux"));
        assertFalse(skills.contains("NestJS"));
        assertFalse(skills.contains("NX,"));
        assertFalse(skills.contains("Typescript"));
    }

    @Test
    void repairFailureDoesNotAdmitEnhancedEntryButKeepsSkills() {
        CountingGenerator generator = new CountingGenerator(false);
        V3EnhancedRunResult result = run(generator);
        assertEquals(1, generator.repairs.get());
        assertTrue(result.entryReviews().stream().noneMatch(review ->
                PRIME.equals(review.getCanonicalEntryKey())
                        && "EVIDENCE_ENHANCED".equals(review.getSuggestionType())));
        assertTrue(result.entryReviews().stream().noneMatch(review ->
                review.getUsedEvidenceItemIds() != null && review.getUsedEvidenceItemIds().contains(61L)));
        assertFalse(result.skillReviews().isEmpty());
        assertEquals(V3EvidenceUsage.USED_IN_SKILLS, usage(result, 61L).getUsageStatus());
        assertEquals(V3EvidenceUsage.USED_IN_SKILLS, usage(result, 62L).getUsageStatus());
        assertEquals(V3EvidenceUsage.USED_IN_SKILLS, usage(result, 63L).getUsageStatus());
        assertEquals(V3EvidenceUsage.SAVED_NOT_USED, usage(result, 64L).getUsageStatus());
        assertEquals(V3EvidenceUsage.SAVED_NOT_USED, usage(result, 65L).getUsageStatus());
    }

    @Test
    void reviewerCannotRestoreMissingConfirmedEvidenceAsEnhanced() {
        CountingGenerator generator = new CountingGenerator(false);
        V3EnhancedRunResult result = new V3EnhancedTailorService(generator, (jobDescription, entries) -> {
            List<V3ResumeLanguageReviewer.Choice> choices = new ArrayList<>();
            for (V3ResumeLanguageReviewer.EntryComparison entry : entries) {
                choices.add(new V3ResumeLanguageReviewer.Choice(
                        entry.canonicalEntryKey(), V3SelectionChoice.JD_TARGETED, "force-targeted"));
            }
            return choices;
        }).run(nodes(), JD, evidence());
        assertTrue(result.entryReviews().stream().noneMatch(review ->
                "EVIDENCE_ENHANCED".equals(review.getSuggestionType())
                        && review.getUsedEvidenceItemIds().contains(61L)));
        assertTrue(result.traces().stream()
                .filter(trace -> PRIME.equals(trace.canonicalEntryKey()))
                .noneMatch(trace -> V3ValidationDecision.MISSING_CONFIRMED_EVIDENCE.equals(trace.validatorCode())
                        && trace.chosenVariant() == V3SelectionChoice.JD_TARGETED
                        && "EVIDENCE_ENHANCED".equals(primeSuggestion(result))));
    }

    @Test
    void grammarRewriteIsNotLabeledEnhanced() {
        V3EnhancedRunResult result = run(new CountingGenerator(false));
        assertTrue(result.entryReviews().stream().noneMatch(review ->
                PRIME.equals(review.getCanonicalEntryKey())
                        && "EVIDENCE_ENHANCED".equals(review.getSuggestionType())));
        assertTrue(result.entryReviews().stream()
                .filter(review -> PRIME.equals(review.getCanonicalEntryKey()))
                .noneMatch(review -> review.getWhyBetter() != null
                        && review.getWhyBetter().toLowerCase(Locale.ROOT).contains("kafka")));
    }

    @Test
    void rescanMarksConfirmedRequirementsCovered() {
        V3OpportunityMap before = new V3EnhancedOpportunityService().scan(nodes(), JD);
        V3OpportunityMap after = new V3EnhancedOpportunityService().scan(nodes(), JD, List.of(nx(), api(), kafka()));
        assertEquals(0, before.writerCalls());
        assertEquals(0, after.writerCalls());
        assertFalse(statusCovered(before, "nx"));
        assertFalse(statusCovered(before, "kafka"));
        assertTrue(statusCovered(after, "nx") || statusCovered(after, "monorepo"));
        assertTrue(statusCovered(after, "api") || statusCovered(after, "contract"));
        assertTrue(statusCovered(after, "kafka") || statusCovered(after, "event-driven"));
        assertTrue(after.questions().stream().noneMatch(question ->
                "nx-monorepo".equals(question.topic())
                        || "api-contract".equals(question.topic())
                        || "event-driven".equals(question.topic())));
    }

    @Test
    void appendOnlySkillsPreservePrefixAndCasing() {
        V3SkillsProjector.Result projected = V3SkillsProjector.project(
                nodes(), List.of(nx(), api(), kafka()));
        String proposed = projected.reviews().get(0).getProposedSkills();
        assertEquals(SKILLS, proposed.substring(0, SKILLS.length()));
        assertTrue(proposed.contains("C/C++"));
        assertTrue(proposed.contains("Unix/Linux"));
        assertTrue(proposed.contains("Javascript"));
        assertTrue(proposed.contains("Kafka"));
        assertTrue(proposed.contains("REST APIs"));
        assertTrue(proposed.contains("TypeScript"));
        assertTrue(proposed.contains("Nx"));
        assertFalse(proposed.contains("NestJS"));
        assertFalse(projected.reviews().get(0).getAddedSkills().contains("retry"));
        assertFalse(projected.reviews().get(0).getAddedSkills().contains("idempotency"));
    }

    private static String primeSuggestion(V3EnhancedRunResult result) {
        return result.entryReviews().stream()
                .filter(review -> PRIME.equals(review.getCanonicalEntryKey()))
                .map(V3EntryReview::getSuggestionType)
                .findFirst()
                .orElse("");
    }

    private static boolean statusCovered(V3OpportunityMap map, String needle) {
        return map.entries().stream()
                .filter(entry -> PRIME.equals(entry.canonicalEntryKey()))
                .flatMap(entry -> entry.requirements().stream())
                .anyMatch(item -> V3JdRequirementBreakdown.COVERED.equals(item.status())
                        && item.requirementText().toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)));
    }

    private static V3EnhancedRunResult run(V3EnhancedEntryRewriteGenerator generator) {
        return new V3EnhancedTailorService(generator, V3RewriteTestSupport.acceptBestAi())
                .run(nodes(), JD, evidence());
    }

    private static V3EntryReview primeReview(V3EnhancedRunResult result) {
        return result.entryReviews().stream()
                .filter(review -> PRIME.equals(review.getCanonicalEntryKey()))
                .findFirst()
                .orElseThrow();
    }

    private static V3EvidenceUsage usage(V3EnhancedRunResult result, long id) {
        return result.evidenceUsage().stream()
                .filter(item -> Long.valueOf(id).equals(item.getEvidenceItemId()))
                .findFirst()
                .orElseThrow();
    }

    private static List<V3UserEvidence> evidence() {
        return List.of(nx(), api(), kafka(), primeMetric(), smartMetric());
    }

    private static V3UserEvidence nx() {
        return confirmed(61L, PRIME, "nx-monorepo", NX_TEXT);
    }

    private static V3UserEvidence api() {
        return confirmed(62L, PRIME, "api-contract", API_TEXT);
    }

    private static V3UserEvidence kafka() {
        return confirmed(63L, PRIME, "event-driven", KAFKA_TEXT);
    }

    private static V3UserEvidence primeMetric() {
        return confirmed(64L, PRIME, "metric", PRIME_METRIC);
    }

    private static V3UserEvidence smartMetric() {
        return confirmed(65L, SMART, "metric", SMART_METRIC);
    }

    private static V3UserEvidence confirmed(Long id, String entry, String topic, String action) {
        return new V3UserEvidence(id, entry, "CONFIRMED_USED", action, "", "", "", topic, topic);
    }

    private static List<ResumeDocumentNode> nodes() {
        List<ResumeDocumentNode> nodes = new ArrayList<>();
        nodes.add(node("p-10", "TECHNICAL SKILLS"));
        nodes.add(node("p-11", SKILLS));
        nodes.add(node("p-0", "RELEVANT PROJECTS"));
        nodes.add(node("p-1", "Prime Deals | Personal Project | Sept 2024 - Present"));
        nodes.add(node("p-2", "Led the development of a shopping website in collaboration with a friend."));
        nodes.add(node("p-3", "Defined project scope, clarified requirements, and used UML class diagrams for planning."));
        nodes.add(node("p-4", "Designed and implemented the application using Java and Spring Boot in an MVC pattern."));
        nodes.add(node("p-5", "Integrated RESTful APIs for user authentication, product updates, and shopping cart management."));
        nodes.add(node("p-6", "Deployed the initial version of the project to AWS."));
        nodes.add(node("p-7", "SmartPC Builder | Example University | Jan 2025 - Mar 2025"));
        nodes.add(node("p-8", "Developed a Java Maven project that helps users select optimal components within a given budget"));
        nodes.add(node("p-9", "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)"));
        return nodes;
    }

    private static String originalPrime() {
        return String.join("\n",
                "Led the development of a shopping website in collaboration with a friend.",
                "Defined project scope, clarified requirements, and used UML class diagrams for planning.",
                "Designed and implemented the application using Java and Spring Boot in an MVC pattern.",
                "Integrated RESTful APIs for user authentication, product updates, and shopping cart management.",
                "Deployed the initial version of the project to AWS.");
    }

    private static String grammarPrime() {
        return String.join("\n",
                "Led the development of a shopping website in collaboration with a friend.",
                "Defined project scope, clarified requirements, and used UML class diagrams for planning.",
                "Designed and implemented the application using Java and Spring Boot in an MVC pattern.",
                "Integrated RESTful APIs for user authentication, product updates, and shopping cart management.",
                "Deployed the initial version of the project to AWS.");
    }

    private static String fullPrime() {
        return String.join("\n", fullPrimeBullets());
    }

    private static final class CountingGenerator implements V3EnhancedEntryRewriteGenerator {
        private final boolean repairSucceeds;
        private final AtomicInteger repairs = new AtomicInteger();

        private CountingGenerator(boolean repairSucceeds) {
            this.repairSucceeds = repairSucceeds;
        }

        @Override
        public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
            if (!PRIME.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            if (request.repair()) {
                repairs.incrementAndGet();
                assertTrue(request.repairError().contains(V3ValidationDecision.MISSING_CONFIRMED_EVIDENCE));
                assertTrue(request.repairError().toLowerCase(Locale.ROOT).contains("nx"));
                return rewrite(request, repairSucceeds ? fullPrimeBullets() : grammarPrimeBullets());
            }
            return rewrite(request, grammarPrimeBullets());
        }

        private static V3EntryRewriteResult rewrite(V3EntryRewriteRequest request, String[] bullets) {
            return V3RewriteTestSupport.rewriteBoth(request, bullets);
        }
    }

    private static String[] grammarPrimeBullets() {
        return originalPrime().split("\n");
    }

    private static String[] fullPrimeBullets() {
        return new String[] {
                "Led the development of a shopping website in collaboration with a friend.",
                "Defined project scope, clarified requirements, and used UML class diagrams for planning.",
                "Designed and implemented the application using Java and Spring Boot in an MVC pattern in an Nx monorepo with Vue/TypeScript shared libraries, the dependency graph, and affected builds.",
                "Integrated RESTful APIs for user authentication, product updates, and shopping cart management with JSON request/response contracts, standardized validation and error responses, and /api/v1 versioning.",
                "Deployed the initial version of the project to AWS with Kafka event processing, bounded retries, a dead-letter topic, idempotent consumers, and versioned event schemas."
        };
    }
}
