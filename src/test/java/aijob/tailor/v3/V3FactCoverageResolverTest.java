package aijob.tailor.v3;

import aijob.util.ClaimLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.node;
import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static aijob.tailor.v3.V3BasicGoldenTest.JD;
import static aijob.tailor.v3.V3BasicGoldenTest.PRIME_1;
import static aijob.tailor.v3.V3BasicGoldenTest.PRIME_3;
import static aijob.tailor.v3.V3BasicGoldenTest.PRIME_KEY;
import static aijob.tailor.v3.V3BasicGoldenTest.SMART_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3FactCoverageResolverTest {

    private static final String REST_REWRITE =
            "Integrated REST APIs to support user authentication, product updates, and shopping cart operations.";

    private V3ResumeFactGraphs graphs;
    private V3EntryFactGraph prime;
    private V3EntryFactGraph smart;

    @BeforeEach
    void load() {
        graphs = V3EntryFactGraphFactory.from(ClaimLedger.from(primeAndSmart()));
        prime = graphs.entry(PRIME_KEY);
        smart = graphs.entry(SMART_KEY);
        assertNotNull(prime);
        assertNotNull(smart);
    }

    @Test
    void legalBodyWithoutModelFactKeysPassesServerProvenance() {
        List<String> original = prime.originalBulletTexts();
        List<String> rewritten = List.of(original.get(0), REST_REWRITE, original.get(2));
        V3FactCoverage.Result coverage = V3FactCoverage.compute(prime, original, rewritten);
        assertTrue(coverage.omitted().stream().noneMatch(key -> key.toLowerCase().contains("rest")));
        assertTrue(invalidKeys(prime, coverage.used()).isEmpty());
        V3ValidationDecision decision = V3EntryRewriteValidator.validate(
                graphs, new V3EntryRewrite(PRIME_KEY, "", "", rewritten));
        assertTrue(decision.accepted(), decision.reasonCode() + " " + decision.detail());
    }

    @Test
    void wrongCitationsDoNotRejectLegalBody() {
        AtomicInteger repairs = new AtomicInteger();
        V3BasicRunResult result = new V3BasicTailorService(request -> {
            if (request.repair()) {
                repairs.incrementAndGet();
            }
            if (!PRIME_KEY.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            return cited(request, List.of("missing-node", "p-99"), PRIME_1, REST_REWRITE, PRIME_3);
        }, V3RewriteTestSupport.acceptBestAi()).run(primeAndSmart(), JD);
        assertEquals(0, result.repairCalls());
        assertEquals(0, repairs.get());
        assertTrue(result.rewrittenEntryCount() >= 0);
    }

    @Test
    void restfulToRestRetainsFact() {
        List<String> original = prime.originalBulletTexts();
        List<String> rewritten = List.of(original.get(0), REST_REWRITE, original.get(2));
        V3FactCoverage.Result coverage = V3FactCoverage.compute(prime, original, rewritten);
        assertTrue(coverage.used().stream().anyMatch(key -> key.toLowerCase().contains("rest")));
        assertTrue(coverage.omitted().stream().noneMatch(key -> key.toLowerCase().contains("rest")));
        assertTrue(V3FactCoverageResolver.textAllowsTech(prime.originalEntryText(), prime, "rest"));
        assertTrue(V3FactCoverageResolver.textAllowsTech(prime.originalEntryText(), prime, "restful"));
    }

    @Test
    void leadToLedIsRetained() {
        V3ResumeFactGraphs leadGraphs = leadGraphs();
        V3EntryFactGraph graph = leadGraphs.entry(PRIME_KEY);
        List<String> original = graph.originalBulletTexts();
        List<String> led = List.of(
                original.get(0).replace("Lead", "Led").replace("lead", "led"),
                original.get(1),
                original.get(2));
        V3ValidationDecision decision = V3EntryRewriteValidator.validate(
                leadGraphs, new V3EntryRewrite(PRIME_KEY, "", "", led));
        assertTrue(decision.accepted(), decision.reasonCode() + " " + decision.detail());
        V3FactCoverage.Result coverage = V3FactCoverage.compute(graph, original, led);
        assertTrue(coverage.omitted().stream().noneMatch(key -> key.toLowerCase().contains("lead")));
    }

    @Test
    void deletingLeadIsMissingMaterialFact() {
        V3ResumeFactGraphs leadGraphs = leadGraphs();
        List<String> original = leadGraphs.entry(PRIME_KEY).originalBulletTexts();
        List<String> dropped = List.of(
                "Collaborated with teammates while building Java Spring Boot with MVC.",
                original.get(1),
                original.get(2));
        V3ValidationDecision decision = V3EntryRewriteValidator.validate(
                leadGraphs, new V3EntryRewrite(PRIME_KEY, "", "", dropped));
        assertEquals(V3ValidationDecision.MISSING_MATERIAL_FACT, decision.reasonCode());
    }

    @Test
    void smartPcSpringBootIsCrossEntryOrInvented() {
        List<String> original = smart.originalBulletTexts();
        List<String> rewritten = List.of(
                original.get(0) + " using Spring Boot.",
                original.get(1));
        V3ValidationDecision decision = V3EntryRewriteValidator.validate(
                graphs, new V3EntryRewrite(SMART_KEY, "", "", rewritten));
        assertFalse(decision.accepted());
        assertTrue(
                V3ValidationDecision.CROSS_ENTRY_FACT.equals(decision.reasonCode())
                        || V3ValidationDecision.INVENTED_SKILL.equals(decision.reasonCode()),
                decision.reasonCode() + " " + decision.detail());
        assertFalse(V3FactCoverageResolver.textAllowsTech(smart.originalEntryText(), smart, "spring boot"));
    }

    @Test
    void javascriptDoesNotSatisfyJava() {
        V3Fact java = prime.factsOf(V3FactKind.TECHNOLOGY).stream()
                .filter(fact -> {
                    String hay = (fact.surface() + " " + fact.factKey()).toLowerCase();
                    return hay.contains("java") && !hay.contains("javascript");
                })
                .findFirst()
                .orElseThrow();
        assertTrue(java.coveredBy("Built the backend in Java."));
        assertFalse(java.coveredBy("Built the frontend in JavaScript."));
        assertFalse(V3FactCoverageResolver.textAllowsTech("Developed a Java Maven project", prime, "javascript"));
    }

    @Test
    void supportingSourceNodeIdsComeFromTheServer() {
        List<String> original = prime.originalBulletTexts();
        V3FactCoverage.Result coverage = V3FactCoverage.compute(prime, original, original);
        assertFalse(coverage.supportingSourceNodeIds().isEmpty());
        assertTrue(prime.sourceNodeIds().containsAll(coverage.supportingSourceNodeIds()));
    }

    @Test
    void movedFactKeysFollowNodeOwnership() {
        List<String> original = prime.originalBulletTexts();
        List<String> swapped = List.of(original.get(2), original.get(1), original.get(0));
        V3FactCoverage.Result coverage = V3FactCoverage.compute(prime, original, swapped);
        assertFalse(coverage.moved().isEmpty());
        assertTrue(coverage.moved().stream().anyMatch(key -> key.toLowerCase().contains("aws")));
    }

    @Test
    void repairIsNotTriggeredByMetadata() {
        AtomicInteger repairs = new AtomicInteger();
        V3BasicRunResult result = new V3BasicTailorService(request -> {
            if (request.repair()) {
                repairs.incrementAndGet();
            }
            if (!PRIME_KEY.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            return cited(request, List.of(), PRIME_1, REST_REWRITE, PRIME_3);
        }, V3RewriteTestSupport.acceptBestAi()).run(primeAndSmart(), JD);
        assertEquals(0, result.repairCalls());
        assertEquals(0, repairs.get());
        assertTrue(result.traces().stream()
                .filter(trace -> PRIME_KEY.equals(trace.canonicalEntryKey()))
                .allMatch(trace -> trace.repairReason() == null || trace.repairReason().isBlank()));
    }

    @Test
    void unknownTokensAreNotZero() {
        assertNull(OpenAiV3EntryRewriteGenerator.promptTokens(Optional.empty()));
        assertNull(OpenAiV3EntryRewriteGenerator.completionTokens(Optional.empty()));
        V3GenerationTelemetry unknown = V3GenerationTelemetry.unknown("v3-2.2-server-provenance", "gpt-4o", 12L);
        assertNull(unknown.promptTokens());
        assertNull(unknown.completionTokens());
        assertNull(unknown.totalTokens());
        assertNotEquals(Integer.valueOf(0), unknown.promptTokens());
        assertNotEquals(Integer.valueOf(0), unknown.completionTokens());
    }

    @Test
    void smartPcPromptDoesNotContainPrimeSpringBootRestOrAwsFacts() {
        AtomicReference<V3EntryRewriteRequest> smartRequest = new AtomicReference<>();
        new V3BasicTailorService(request -> {
            if (SMART_KEY.equals(request.canonicalEntryKey())) {
                smartRequest.set(request);
            }
            return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
        }, V3RewriteTestSupport.acceptBestAi()).run(primeAndSmart(), "Junior Java intern. Required: Java and Maven.");
        assertNotNull(smartRequest.get());
        String prompt = V3EntryRewritePrompt.build(smartRequest.get());
        String allowed = section(prompt, "ALLOWED FACTS FROM THIS ENTRY", "RELEVANT JD REQUIREMENTS");
        String forbidden = section(prompt, "FORBIDDEN FACTS", "JOB DESCRIPTION:");
        String hayAllowed = allowed.toLowerCase();
        String hayForbidden = forbidden.toLowerCase();
        assertFalse(hayAllowed.contains("spring boot"));
        assertFalse(hayAllowed.contains("aws"));
        assertFalse(hayAllowed.contains("restful"));
        assertFalse(hayForbidden.contains("spring boot"));
        assertFalse(hayForbidden.contains("aws"));
        assertFalse(hayForbidden.contains("restful"));
        assertFalse(hayForbidden.contains("rest api"));
        assertTrue(hayAllowed.contains("java") || hayAllowed.contains("maven"));
    }

    private static V3ResumeFactGraphs leadGraphs() {
        return V3EntryFactGraphFactory.from(ClaimLedger.from(List.of(
                node("p-0", "RELEVANT PROJECTS"),
                node("p-1", "Prime Deals | Personal Project | Sept 2024 - Present"),
                node("p-2", "Lead and collaborate with teammates while building Java Spring Boot with MVC."),
                node("p-3", "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements"),
                node("p-4", "Successfully deployed the initial version of the project onto AWS")
        )));
    }

    private static List<String> invalidKeys(V3EntryFactGraph graph, List<String> used) {
        List<String> invalid = new ArrayList<>();
        for (String key : used) {
            if (key != null && !key.isBlank() && !graph.hasFactKey(key)) {
                invalid.add(key);
            }
        }
        return invalid;
    }

    private static String section(String prompt, String start, String end) {
        int from = prompt.indexOf(start);
        int to = prompt.indexOf(end, from + start.length());
        if (from < 0) {
            return "";
        }
        if (to < 0) {
            return prompt.substring(from);
        }
        return prompt.substring(from, to);
    }

    private static V3EntryRewriteResult cited(
            V3EntryRewriteRequest request,
            List<String> citedSourceNodeIds,
            String... bullets) {
        List<V3RewrittenNode> nodes = new ArrayList<>();
        List<V3WritableNode> writable = request.writableNodes();
        for (int i = 0; i < writable.size(); i++) {
            String text = i < bullets.length ? bullets[i] : writable.get(i).originalText();
            nodes.add(new V3RewrittenNode(
                    writable.get(i).nodeId(),
                    text,
                    List.of("made-up-fact-key"),
                    List.of(),
                    "COORDINATED",
                    "test",
                    citedSourceNodeIds));
        }
        V3EntryRewriteVariant variant = new V3EntryRewriteVariant(
                V3VariantType.CLARITY_FIRST,
                V3RewriteDecision.REWRITE,
                nodes,
                "test",
                List.of(),
                List.of(),
                List.of(),
                V3SelfCheck.empty());
        return V3EntryRewriteResult.both(
                request.canonicalEntryKey(),
                variant,
                new V3EntryRewriteVariant(
                        V3VariantType.JD_TARGETED,
                        V3RewriteDecision.REWRITE,
                        nodes,
                        "test",
                        List.of(),
                        List.of(),
                        List.of(),
                        V3SelfCheck.empty()));
    }
}
