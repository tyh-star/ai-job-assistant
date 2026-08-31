package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.node;
import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3BasicGoldenTest {

    static final String JD = """
            Junior Java backend intern.
            Required: Java, Spring Boot, MVC, RESTful APIs, authentication, AWS.
            Also Maven, object-oriented design, algorithms, CPU GPU RAM, and documentation.
            """;

    static final String PRIME_KEY = "PROJECT:prime-deals";
    static final String SMART_KEY = "PROJECT:smartpc-builder";

    static final String PRIME_1 =
            "Designed and implemented the application in Java Spring Boot using MVC.";
    static final String PRIME_2 =
            "Integrated RESTful APIs to support user authentication, product updates, and shopping cart operations.";
    static final String PRIME_3 =
            "Deployed the initial version of the project to AWS.";
    static final String SMART_1 =
            "Developed a Java Maven project that selects optimal components within a given budget.";
    static final String SMART_2 =
            "Implemented an algorithm that dynamically suggests CPU, GPU, and RAM components.";

    @Test
    void oneGenerateCallPerEntryRewritesCoordinatedFacts() {
        RecordingGenerator generator = new RecordingGenerator(request -> {
            if (PRIME_KEY.equals(request.canonicalEntryKey())) {
                return rewrite(request, PRIME_1, PRIME_2, PRIME_3);
            }
            if (SMART_KEY.equals(request.canonicalEntryKey())) {
                return rewrite(request, SMART_1, SMART_2);
            }
            return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
        });
        V3BasicTailorService service = new V3BasicTailorService(generator, V3RewriteTestSupport.acceptBestAi());

        V3BasicRunResult result = service.run(primeAndSmart(), JD);

        assertEquals(2, result.generateCalls());
        assertEquals(0, result.repairCalls());
        assertEquals(1, result.languageReviewCalls());
        assertEquals(1, generator.calls.get(PRIME_KEY).intValue());
        assertEquals(1, generator.calls.get(SMART_KEY).intValue());
        assertEquals(2, result.rewrittenEntryCount());
        assertEquals(5, result.changes().size());
        String prime = joined(result, PRIME_KEY);
        assertCovers(prime, "java", "spring boot", "mvc", "rest", "authentication", "product", "cart", "aws");
        assertFalse(prime.toLowerCase(Locale.ROOT).contains("executed the deployment"));
        assertNoDuplicate(result, PRIME_KEY, "java");
        assertNoDuplicate(result, PRIME_KEY, "aws");
        String smart = joined(result, SMART_KEY);
        assertCovers(smart, "java", "maven", "budget", "algorithm", "cpu", "gpu", "ram");
        assertFalse(smart.toLowerCase(Locale.ROOT).contains("spring boot"));
        assertFalse(smart.toLowerCase(Locale.ROOT).contains("aws"));
        assertFalse(smart.toLowerCase(Locale.ROOT).contains("rest"));
        V3BasicRunResult.V3EntryTrace primeTrace = trace(result, PRIME_KEY);
        assertEquals(V3RewriteDecision.REWRITE, primeTrace.decision());
        assertTrue(primeTrace.qualityImproved());
        assertTrue(primeTrace.languageAccepted());
    }

    @Test
    void sameEntryRedistributionPassesAndDuplicatesOrLossFailUntilRepair() {
        RecordingGenerator generator = new RecordingGenerator(request -> {
            if (!PRIME_KEY.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            if (!request.repair()) {
                return rewrite(request,
                        "Designed and implemented the application in Java Spring Boot using MVC.",
                        "Integrated Java RESTful APIs to support user authentication.",
                        "Deployed the initial version of the project.");
            }
            return rewrite(request, PRIME_1, PRIME_2, PRIME_3);
        });
        V3BasicRunResult result = new V3BasicTailorService(generator, V3RewriteTestSupport.acceptBestAi()).run(primeAndSmart(), JD);
        assertEquals(2, result.generateCalls());
        assertEquals(1, result.repairCalls());
        assertEquals(2, generator.calls.get(PRIME_KEY).intValue());
        assertEquals(1, result.rewrittenEntryCount());
        assertCovers(joined(result, PRIME_KEY), "java", "spring boot", "rest", "aws");
        assertNoDuplicate(result, PRIME_KEY, "java");
    }

    @Test
    void repairIsAtMostOnceThenNoChange() {
        RecordingGenerator generator = new RecordingGenerator(request -> {
            if (!PRIME_KEY.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            return rewrite(request,
                    "Designed and implemented the application in Java Spring Boot using MVC.",
                    "Integrated Java RESTful APIs to support user authentication.",
                    "Deployed the initial version of the project.");
        });
        V3BasicRunResult result = new V3BasicTailorService(generator, V3RewriteTestSupport.acceptBestAi()).run(primeAndSmart(), JD);
        assertEquals(2, generator.calls.get(PRIME_KEY).get());
        assertEquals(1, result.repairCalls());
        assertTrue(result.changes().stream().noneMatch(change -> "p-2".equals(change.getNodeId())
                || "p-3".equals(change.getNodeId())
                || "p-4".equals(change.getNodeId())));
        assertEquals(V3RewriteDecision.NO_CHANGE, trace(result, PRIME_KEY).decision());
    }

    @Test
    void crossEntryNewNumberAndRoleEscalationStayNoChange() {
        RecordingGenerator generator = new RecordingGenerator(request -> {
            if (SMART_KEY.equals(request.canonicalEntryKey())) {
                return rewrite(request,
                        "Developed a Java Maven Spring Boot project that selects components within a given budget.",
                        SMART_2);
            }
            if (PRIME_KEY.equals(request.canonicalEntryKey())) {
                return rewrite(request,
                        "Led the Java Spring Boot application using MVC.",
                        "Integrated RESTful APIs and improved conversion by 40%.",
                        PRIME_3);
            }
            return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
        });
        V3BasicRunResult result = new V3BasicTailorService(generator, V3RewriteTestSupport.acceptBestAi()).run(primeAndSmart(), JD);
        assertEquals(2, result.repairCalls());
        assertEquals(0, result.rewrittenEntryCount());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    void communicationSynonymSwapIsNoChange() {
        List<ResumeDocumentNode> nodes = new ArrayList<>(primeAndSmart());
        nodes.add(node("p-8", "● Communicated effectively with TA and Professor to clarify requirements."));
        RecordingGenerator generator = new RecordingGenerator(request -> {
            if (PRIME_KEY.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            if (SMART_KEY.equals(request.canonicalEntryKey())) {
                return rewrite(request,
                        request.writableNodes().get(0).originalText(),
                        request.writableNodes().get(1).originalText(),
                        "● Engaged with TA and Professor to clarify requirements.");
            }
            return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
        });
        V3BasicRunResult result = new V3BasicTailorService(generator, V3RewriteTestSupport.acceptBestAi()).run(nodes, JD);
        assertTrue(result.changes().stream().noneMatch(change -> "p-8".equals(change.getNodeId())));
        assertEquals(V3RewriteDecision.NO_CHANGE, trace(result, SMART_KEY).decision());
    }

    @Test
    void languageReviewRejectsAwkwardNominalization() {
        RecordingGenerator generator = new RecordingGenerator(request -> {
            if (PRIME_KEY.equals(request.canonicalEntryKey())) {
                return rewrite(request, PRIME_1, PRIME_2,
                        "Performed the integration of the project's initial version onto AWS.");
            }
            return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
        });
        V3ResumeLanguageReviewer reviewer = (jobDescription, entries) -> {
            List<V3ResumeLanguageReviewer.Choice> choices = new ArrayList<>();
            for (V3ResumeLanguageReviewer.EntryComparison entry : entries) {
                boolean awkward = entry.jdTargeted() != null
                        && entry.jdTargeted().rewrittenNodes().stream()
                        .anyMatch(node -> node.newText().toLowerCase(Locale.ROOT)
                                .contains("performed the integration"));
                choices.add(new V3ResumeLanguageReviewer.Choice(
                        entry.canonicalEntryKey(),
                        awkward ? V3SelectionChoice.REJECT_ALL_AI : V3SelectionChoice.JD_TARGETED,
                        awkward ? "awkward" : "ok"));
            }
            return choices;
        };
        V3BasicRunResult result = new V3BasicTailorService(generator, reviewer).run(primeAndSmart(), JD);
        String prime = joined(result, PRIME_KEY);
        assertFalse(prime.toLowerCase(Locale.ROOT).contains("performed the integration"), prime);
        assertTrue(result.changes().stream().noneMatch(change ->
                change.getNewText() != null
                        && change.getNewText().toLowerCase(Locale.ROOT).contains("performed the integration")));
    }

    @Test
    void judgeExceptionKeepsLocalValidatedRewrite() {
        RecordingGenerator generator = new RecordingGenerator(request -> {
            if (PRIME_KEY.equals(request.canonicalEntryKey())) {
                return rewrite(request, PRIME_1, PRIME_2, PRIME_3);
            }
            return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
        });
        V3ResumeLanguageReviewer reviewer = (jobDescription, entries) -> {
            throw new IllegalStateException("judge down");
        };
        V3BasicRunResult result = new V3BasicTailorService(generator, reviewer).run(primeAndSmart(), JD);
        assertEquals(1, result.languageReviewCalls());
        assertEquals(1, result.rewrittenEntryCount());
        assertEquals(V3RewriteDecision.REWRITE, trace(result, PRIME_KEY).decision());
        assertFalse(result.changes().isEmpty());
    }

    @Test
    void noChangeEmitsNoWriterChanges() {
        RecordingGenerator generator = new RecordingGenerator(
                request -> V3EntryRewriteResult.noChange(request.canonicalEntryKey()));
        V3BasicRunResult result = new V3BasicTailorService(generator, V3RewriteTestSupport.acceptBestAi()).run(primeAndSmart(), JD);
        assertEquals(2, result.generateCalls());
        assertEquals(0, result.repairCalls());
        assertEquals(0, result.languageReviewCalls());
        assertTrue(result.changes().isEmpty());
        assertEquals(0, result.rewrittenEntryCount());
    }

    @Test
    void equalCountAdapterSkipsUnchangedAndNeverWritesHeadings() {
        List<V3WritableNode> writable = List.of(
                new V3WritableNode("p-2", "Design and implement the application using Java Spring Boot with an MVC pattern."),
                new V3WritableNode("p-3", "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements"),
                new V3WritableNode("p-4", "Successfully deployed the initial version of the project onto AWS")
        );
        V3EntryRewriteResult result = V3RewriteTestSupport.rewriteBoth(new V3EntryRewriteRequest(
                V3ProductMode.BASIC, PRIME_KEY, "PROJECT", "Prime Deals", "Sept 2024 - Present",
                JD, List.of(), List.of(), writable, null, null, List.of(), List.of(), 3, 400, ""),
                writable.get(0).originalText(), PRIME_2, PRIME_3);
        List<TailorChange> changes = V3EqualCountChangeAdapter.toChanges(result.jdTargeted(), writable);
        assertEquals(2, changes.size());
        assertEquals("p-3", changes.get(0).getNodeId());
        assertEquals("p-4", changes.get(1).getNodeId());
        assertTrue(changes.stream().noneMatch(change -> "p-1".equals(change.getNodeId())));
        assertTrue(V3EqualCountChangeAdapter.toChanges(
                V3EntryRewriteResult.noChange(PRIME_KEY).jdTargeted(), writable).isEmpty());
    }

    private static V3EntryRewriteResult rewrite(V3EntryRewriteRequest request, String... bullets) {
        return V3RewriteTestSupport.rewriteBoth(request, bullets);
    }

    private static String joined(V3BasicRunResult result, String key) {
        return String.join(" ", trace(result, key).finalBullets());
    }

    private static V3BasicRunResult.V3EntryTrace trace(V3BasicRunResult result, String key) {
        return result.traces().stream()
                .filter(item -> key.equals(item.canonicalEntryKey()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertCovers(String text, String... needles) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            assertTrue(lower.contains(needle.toLowerCase(Locale.ROOT)), needle + " missing in " + text);
        }
    }

    private static void assertNoDuplicate(V3BasicRunResult result, String key, String needle) {
        int hits = 0;
        for (String bullet : trace(result, key).finalBullets()) {
            if (bullet.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        assertEquals(1, hits, needle + " duplicated in " + joined(result, key));
    }

    private static final class RecordingGenerator implements V3EntryRewriteGenerator {
        private final java.util.function.Function<V3EntryRewriteRequest, V3EntryRewriteResult> delegate;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        private RecordingGenerator(
                java.util.function.Function<V3EntryRewriteRequest, V3EntryRewriteResult> delegate) {
            this.delegate = delegate;
        }

        @Override
        public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
            calls.computeIfAbsent(request.canonicalEntryKey(), ignored -> new AtomicInteger()).incrementAndGet();
            return delegate.apply(request);
        }
    }
}
