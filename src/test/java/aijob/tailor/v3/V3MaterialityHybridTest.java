package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.util.ClaimLedger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.node;
import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3MaterialityHybridTest {

    static final String PRIME_KEY = "PROJECT:prime-deals";
    static final String SMART_KEY = "PROJECT:smartpc-builder";

    static final List<String> PRIME_ORIGINAL = List.of(
            "Lead and collaborate with a friend to develop a shopping website",
            "Define project scope, clarify requirements and utilized UML Class Diagram for planning",
            "Design and implement the application using Java Spring Boot with an MVC pattern.",
            "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements",
            "Successfully deployed the initial version of the project onto AWS"
    );
    static final List<String> PRIME_CLARITY = List.of(
            "Led and collaborated on the development of a shopping website.",
            "Defined project scope and requirements, utilizing UML Class Diagrams for planning.",
            "Designed and implemented the application using Java Spring Boot with an MVC pattern.",
            "Integrated RESTful APIs for user authentication, product updates, and shopping cart management.",
            "Deployed the initial version of the project onto AWS."
    );
    static final List<String> PRIME_TARGETED = List.of(
            "Led and collaborated on developing a shopping website.",
            "Defined project scope and requirements using UML Class Diagrams for planning.",
            "Designed and implemented the application using Java and Spring Boot with an MVC architecture.",
            "Integrated REST APIs for managing user authentication, product updates, and shopping cart functions.",
            "Deployed the initial version of the project onto AWS."
    );
    static final List<String> SMART_ORIGINAL = List.of(
            "Developed a Java Maven project that helps users select optimal components within a given budget",
            "Applied Object-Oriented Programming (OOP) principles, leveraging inheritance and encapsulation for modularity",
            "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)",
            "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality",
            "Communicated effectively with TA and Professor to clarify project requirements and resolve ambiguities"
    );
    static final List<String> SMART_CLARITY = List.of(
            "Developed a Java Maven project enabling users to choose optimal PC components within a budget.",
            "Utilized OOP principles, including inheritance and encapsulation, to enhance code modularity.",
            "Implemented an algorithm that dynamically suggests components like CPU, GPU, and RAM.",
            "Delivered clean, well-documented code with thorough testing to ensure quality.",
            "Communicated effectively with TA and Professor to clarify project requirements and resolve ambiguities."
    );
    static final List<String> SMART_TARGETED = List.of(
            "Created a Java Maven project that helps users select PC components based on budget constraints.",
            "Applied OOP principles like inheritance and encapsulation to support modular, MVC-aligned design.",
            "Crafted an algorithm for dynamic component suggestions such as CPU, GPU, and RAM.",
            "Delivered clean, documented code with thorough testing to maintain high quality.",
            "Communicated with TA and Professor to clarify requirements and resolve ambiguities effectively."
    );

    @Test
    void developmentCoversDevelop() {
        V3Fact develop = fact(V3FactKind.ACTION, "action:develop", "develop");
        assertTrue(develop.coveredBy("Led and collaborated on the development of a shopping website."));
        assertTrue(V3CoverageMorphology.coversAction("development", aijob.util.ActionConceptRegistry.DEVELOP));
    }

    @Test
    void restfulCoversRest() {
        V3ResumeFactGraphs graphs = graphs(fullPrimeAndSmart());
        V3EntryFactGraph prime = graphs.entry(PRIME_KEY);
        V3FactCoverage.Result coverage = V3FactCoverage.compute(prime, PRIME_ORIGINAL, List.of(
                PRIME_ORIGINAL.get(0),
                PRIME_ORIGINAL.get(1),
                PRIME_ORIGINAL.get(2),
                "Integrated REST APIs for user authentication, product updates, and shopping cart operations.",
                PRIME_ORIGINAL.get(4)
        ));
        assertTrue(coverage.omitted().stream().noneMatch(key -> key.toLowerCase(Locale.ROOT).contains("rest")));
        assertTrue(coverage.used().stream().anyMatch(key -> key.toLowerCase(Locale.ROOT).contains("rest")));
    }

    @Test
    void friendIsOptionalContextAndDefineIsNoise() {
        V3EntryFactGraph prime = graphs(fullPrimeAndSmart()).entry(PRIME_KEY);
        V3Fact friend = factNamed(prime, "friend");
        V3Fact define = factNamed(prime, "object:define");
        assertEquals(V3FactMateriality.OPTIONAL_CONTEXT, friend.materiality(), friend.toString());
        assertEquals(V3FactMateriality.EXTRACTION_NOISE, define.materiality(), define.toString());
        List<String> withoutFriend = List.of(
                "Led and collaborated on developing a shopping website.",
                PRIME_ORIGINAL.get(1),
                PRIME_ORIGINAL.get(2),
                PRIME_ORIGINAL.get(3),
                PRIME_ORIGINAL.get(4)
        );
        V3FactCoverage.Result coverage = V3FactCoverage.compute(prime, PRIME_ORIGINAL, withoutFriend);
        assertFalse(coverage.omitted().contains(friend.factKey()));
        assertTrue(coverage.omittedOptional().contains(friend.factKey())
                || !friend.coveredBy(String.join(" ", withoutFriend)));
        assertFalse(coverage.omitted().contains(define.factKey()));
        V3ValidationDecision decision = V3EntryRewriteValidator.validate(
                graphs(fullPrimeAndSmart()),
                new V3EntryRewrite(PRIME_KEY, "", "", withoutFriend));
        assertTrue(decision.accepted(), decision.reasonCode() + " " + decision.detail());
    }

    @Test
    void missingLeadAndJavadocRemainProtected() {
        V3ResumeFactGraphs graphs = graphs(fullPrimeAndSmart());
        V3ValidationDecision noLead = V3EntryRewriteValidator.validate(graphs, new V3EntryRewrite(
                PRIME_KEY, "", "", List.of(
                        "Collaborated on developing a shopping website.",
                        PRIME_ORIGINAL.get(1),
                        PRIME_ORIGINAL.get(2),
                        PRIME_ORIGINAL.get(3),
                        PRIME_ORIGINAL.get(4)
                )));
        assertEquals(V3ValidationDecision.MISSING_MATERIAL_FACT, noLead.reasonCode(), noLead.detail());

        V3ValidationDecision noDoc = V3EntryRewriteValidator.validate(graphs, new V3EntryRewrite(
                SMART_KEY, "", "", List.of(
                        SMART_ORIGINAL.get(0),
                        SMART_ORIGINAL.get(1),
                        SMART_ORIGINAL.get(2),
                        "Delivered clean code with thorough testing to ensure the quality",
                        SMART_ORIGINAL.get(4)
                )));
        assertEquals(V3ValidationDecision.MISSING_MATERIAL_FACT, noDoc.reasonCode(), noDoc.detail());
        assertTrue(noDoc.detail().toLowerCase(Locale.ROOT).contains("doc")
                || noDoc.detail().toLowerCase(Locale.ROOT).contains("document"), noDoc.detail());

        V3ValidationDecision noTest = V3EntryRewriteValidator.validate(graphs, new V3EntryRewrite(
                SMART_KEY, "", "", List.of(
                        SMART_ORIGINAL.get(0),
                        SMART_ORIGINAL.get(1),
                        SMART_ORIGINAL.get(2),
                        "Delivered clean, well-documented (using Java doc string) code to ensure the quality",
                        SMART_ORIGINAL.get(4)
                )));
        assertEquals(V3ValidationDecision.MISSING_MATERIAL_FACT, noTest.reasonCode(), noTest.detail());
        assertTrue(noTest.detail().toLowerCase(Locale.ROOT).contains("test"), noTest.detail());
    }

    @Test
    void javaDoesNotMatchJavascriptAndCartIsNotTeam() {
        V3Fact java = fact(V3FactKind.TECHNOLOGY, "tech:java", "Java");
        assertTrue(java.coveredBy("Built the backend in Java."));
        assertFalse(java.coveredBy("Built the frontend in JavaScript."));
        assertTrue(V3CoverageMorphology.coversJava("Java and Spring Boot"));
        assertFalse(V3CoverageMorphology.coversJava("JavaScript only"));
        assertTrue(V3CoverageMorphology.manageCartIsNotManageTeam("Integrated APIs for shopping cart management"));
        assertFalse(V3CoverageMorphology.containsTeamManagement("Integrated APIs for shopping cart management"));
        assertTrue(V3CoverageMorphology.containsTeamManagement("Managed a team of engineers"));
        V3Fact springBoot = fact(V3FactKind.TECHNOLOGY, "tech:spring boot", "Spring Boot");
        assertTrue(springBoot.coveredBy("using Java and Spring Boot"));
        assertFalse(springBoot.coveredBy("using Spring without boot"));
    }

    @Test
    void hybridPrefersTargetedStackAndClarityRestWithFullFactCoverage() {
        V3ResumeFactGraphs graphs = graphs(fullPrimeAndSmart());
        V3EntryFactGraph prime = graphs.entry(PRIME_KEY);
        List<V3WritableNode> writable = writable("p-a", PRIME_ORIGINAL);
        V3EntryRewriteRequest request = request(prime, writable, graphs);
        V3HybridSelector.Result hybrids = V3HybridSelector.select(
                graphs,
                prime,
                writable,
                PRIME_ORIGINAL,
                V3RewriteTestSupport.variant(V3VariantType.CLARITY_FIRST, request, PRIME_CLARITY.toArray(String[]::new)),
                V3RewriteTestSupport.variant(V3VariantType.JD_TARGETED, request, PRIME_TARGETED.toArray(String[]::new)),
                V3EntryQualityScorer.scoreOriginal(prime, PRIME_ORIGINAL, V3JdRequirementRanker.rank(V3BasicGoldenTest.JD, prime)),
                V3JdRequirementRanker.rank(V3BasicGoldenTest.JD, prime));
        assertTrue(hybrids.hasAdmitted(), String.valueOf(hybrids));
        V3HybridSelector.Hybrid hybrid = hybrids.hybrid1();
        String text = String.join(" ", hybrid.bullets());
        assertTrue(text.toLowerCase(Locale.ROOT).contains("java and spring boot"), text);
        assertTrue(text.toLowerCase(Locale.ROOT).contains("rest"), text);
        assertTrue(hybrid.nodeSources().contains(V3NodeSource.JD_TARGETED), hybrid.nodeSources().toString());
        assertTrue(hybrid.nodeSources().contains(V3NodeSource.CLARITY_FIRST), hybrid.nodeSources().toString());
        assertTrue(hybrid.coverage().omitted().isEmpty(), hybrid.coverage().omitted().toString());
        assertTrue(hybrid.coverage().introduced().isEmpty(), hybrid.coverage().introduced().toString());
        assertTrue(hybrid.coverage().duplicate().isEmpty(), hybrid.coverage().duplicate().toString());
        V3ValidationDecision decision = V3EntryRewriteValidator.validate(
                graphs, new V3EntryRewrite(PRIME_KEY, "", "", hybrid.bullets()));
        assertTrue(decision.accepted(), decision.reasonCode() + " " + decision.detail());
        assertFalse(text.toLowerCase(Locale.ROOT).contains("maven"));
        assertFalse(text.toLowerCase(Locale.ROOT).contains("spring boot") && text.toLowerCase(Locale.ROOT).contains("cpu"));
    }

    @Test
    void originalWinsWhenAllAiVariantsAreUnqualified() {
        V3BasicRunResult result = new V3BasicTailorService(request -> {
            if (!SMART_KEY.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            return V3RewriteTestSupport.rewriteSplit(request, SMART_CLARITY, SMART_TARGETED);
        }, V3RewriteTestSupport.acceptBestAi()).run(fullPrimeAndSmart(), V3BasicGoldenTest.JD);
        V3BasicRunResult.V3EntryTrace smart = trace(result, SMART_KEY);
        assertEquals(V3SelectionChoice.ORIGINAL, smart.chosenVariant(), smart.toString());
        assertEquals(V3RewriteDecision.NO_CHANGE, smart.decision());
        assertEquals(SMART_ORIGINAL, smart.finalBullets());
        assertFalse(String.join(" ", smart.finalBullets()).toLowerCase(Locale.ROOT).contains("mvc"));
    }

    @Test
    void reviewerCannotRestoreValidatorRejectedVariant() {
        V3ResumeLanguageReviewer reviewer = (jobDescription, entries) -> {
            List<V3ResumeLanguageReviewer.Choice> choices = new ArrayList<>();
            for (V3ResumeLanguageReviewer.EntryComparison entry : entries) {
                choices.add(new V3ResumeLanguageReviewer.Choice(
                        entry.canonicalEntryKey(), V3SelectionChoice.JD_TARGETED, "force-rejected"));
            }
            return choices;
        };
        V3BasicRunResult result = new V3BasicTailorService(request -> {
            if (!SMART_KEY.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            return V3RewriteTestSupport.rewriteSplit(request, SMART_CLARITY, SMART_TARGETED);
        }, reviewer).run(fullPrimeAndSmart(), V3BasicGoldenTest.JD);
        V3BasicRunResult.V3EntryTrace smart = trace(result, SMART_KEY);
        assertFalse(smart.finalBullets().stream().anyMatch(bullet -> bullet.contains("MVC")));
        assertTrue(smart.chosenVariant() == V3SelectionChoice.ORIGINAL
                || smart.chosenVariant() == V3SelectionChoice.REJECT_ALL_AI
                || smart.chosenVariant() == V3SelectionChoice.HYBRID_1
                || smart.chosenVariant() == V3SelectionChoice.HYBRID_2);
        if (smart.chosenVariant() == V3SelectionChoice.HYBRID_1 || smart.chosenVariant() == V3SelectionChoice.HYBRID_2) {
            V3ValidationDecision decision = V3EntryRewriteValidator.validate(
                    graphs(fullPrimeAndSmart()),
                    new V3EntryRewrite(SMART_KEY, "", "", smart.finalBullets()));
            assertTrue(decision.accepted(), decision.reasonCode() + " " + decision.detail());
        }
    }

    @Test
    void hybridAdapterKeepsNodeIdCountAndOrder() {
        List<V3WritableNode> writable = writable("p-a", PRIME_ORIGINAL);
        V3EntryRewriteVariant hybrid = V3HybridSelector.select(
                graphs(fullPrimeAndSmart()),
                graphs(fullPrimeAndSmart()).entry(PRIME_KEY),
                writable,
                PRIME_ORIGINAL,
                V3RewriteTestSupport.variant(
                        V3VariantType.CLARITY_FIRST,
                        request(graphs(fullPrimeAndSmart()).entry(PRIME_KEY), writable, graphs(fullPrimeAndSmart())),
                        PRIME_CLARITY.toArray(String[]::new)),
                V3RewriteTestSupport.variant(
                        V3VariantType.JD_TARGETED,
                        request(graphs(fullPrimeAndSmart()).entry(PRIME_KEY), writable, graphs(fullPrimeAndSmart())),
                        PRIME_TARGETED.toArray(String[]::new)),
                V3EntryQualityScorer.scoreOriginal(
                        graphs(fullPrimeAndSmart()).entry(PRIME_KEY),
                        PRIME_ORIGINAL,
                        V3JdRequirementRanker.rank(V3BasicGoldenTest.JD, graphs(fullPrimeAndSmart()).entry(PRIME_KEY))),
                V3JdRequirementRanker.rank(V3BasicGoldenTest.JD, graphs(fullPrimeAndSmart()).entry(PRIME_KEY))
        ).hybrid1().variant();
        List<TailorChange> changes = V3EqualCountChangeAdapter.toChanges(hybrid, writable);
        assertEquals(writable.size(), hybrid.rewrittenNodes().size());
        for (int i = 0; i < writable.size(); i++) {
            assertEquals(writable.get(i).nodeId(), hybrid.rewrittenNodes().get(i).nodeId());
        }
        List<String> changeIds = changes.stream().map(TailorChange::getNodeId).toList();
        List<String> allowed = writable.stream().map(V3WritableNode::nodeId).toList();
        assertTrue(allowed.containsAll(changeIds), changeIds.toString());
        for (int i = 1; i < changeIds.size(); i++) {
            assertTrue(allowed.indexOf(changeIds.get(i - 1)) < allowed.indexOf(changeIds.get(i)));
        }
    }

    @Test
    void parseSelectionAcceptsHybridsAndRejectsUnknownAsOriginal() {
        assertEquals(V3SelectionChoice.HYBRID_1, OpenAiV3ResumeLanguageReviewer.parseSelection("HYBRID_1"));
        assertEquals(V3SelectionChoice.HYBRID_2, OpenAiV3ResumeLanguageReviewer.parseSelection("hybrid 2"));
        assertEquals(V3SelectionChoice.REJECT_ALL_AI, OpenAiV3ResumeLanguageReviewer.parseSelection("REJECT_ALL_AI"));
        assertEquals(V3SelectionChoice.ORIGINAL, OpenAiV3ResumeLanguageReviewer.parseSelection("NEW_TEXT"));
        assertTrue(OpenAiV3ResumeLanguageReviewer.keysMatch("PROJECT:prime-deals", "prime-deals"));
        assertTrue(OpenAiV3ResumeLanguageReviewer.keysMatch("PROJECT:prime-deals", "Prime Deals"));
        assertFalse(OpenAiV3ResumeLanguageReviewer.keysMatch("PROJECT:prime-deals", "PROJECT:smartpc-builder"));
    }

    @Test
    void oopAndAuthenticationMorphologyStayControlled() {
        V3Fact oop = fact(V3FactKind.TECHNOLOGY, "tech:oop", "OOP");
        assertTrue(oop.coveredBy("Applied Object-Oriented Programming principles"));
        assertTrue(oop.coveredBy("Applied OOP principles"));
        V3Fact authentication = fact(V3FactKind.TASK, "task:authentication", "authentication");
        assertTrue(authentication.coveredBy("Integrated APIs for authenticating users and updating products"));
        V3Fact product = fact(V3FactKind.TASK, "task:product", "product");
        assertTrue(product.coveredBy("updating products"));
        assertFalse(product.coveredBy("production environment only"));
    }

    @Test
    void missingReviewerChoiceUsesLocalAdmittedScore() {
        V3ResumeLanguageReviewer silent = (jobDescription, entries) -> List.of();
        V3BasicRunResult result = new V3BasicTailorService(request -> {
            if (!PRIME_KEY.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            return V3RewriteTestSupport.rewriteSplit(request, PRIME_CLARITY, PRIME_TARGETED);
        }, silent).run(fullPrimeAndSmart(), V3BasicGoldenTest.JD);
        V3BasicRunResult.V3EntryTrace prime = trace(result, PRIME_KEY);
        assertTrue(prime.chosenVariant() == V3SelectionChoice.CLARITY_FIRST
                || prime.chosenVariant() == V3SelectionChoice.JD_TARGETED
                || prime.chosenVariant() == V3SelectionChoice.HYBRID_1
                || prime.chosenVariant() == V3SelectionChoice.HYBRID_2, prime.toString());
        assertEquals(V3RewriteDecision.REWRITE, prime.decision());
    }

    @Test
    void shortFixtureIdentityStillSeparateFromLedgerId() {
        ClaimLedger ledger = ClaimLedger.from(primeAndSmart());
        assertEquals(PRIME_KEY, V3CanonicalEntryKey.of(ledger.entryOf("p-2")));
        assertEquals(SMART_KEY, V3CanonicalEntryKey.of(ledger.entryOf("p-6")));
    }

    private static V3Fact fact(V3FactKind kind, String key, String surface) {
        return new V3Fact("", kind, key, surface, List.of(surface), List.of("n1"),
                V3FactSource.RESUME, V3FactPolarity.CONFIRMED, null, V3FactMateriality.PROTECTED);
    }

    private static V3Fact factNamed(V3EntryFactGraph graph, String needle) {
        return graph.facts().stream()
                .filter(item -> (item.factKey() + " " + item.surface()).toLowerCase(Locale.ROOT).contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + needle + " in " + graph.facts()));
    }

    private static V3ResumeFactGraphs graphs(List<ResumeDocumentNode> nodes) {
        return V3EntryFactGraphFactory.from(ClaimLedger.from(nodes));
    }

    static List<ResumeDocumentNode> fullPrimeAndSmart() {
        List<ResumeDocumentNode> nodes = new ArrayList<>();
        nodes.add(node("p-0", "RELEVANT PROJECTS"));
        nodes.add(node("p-1", "Prime Deals | Personal Project | Sept 2024 - Present"));
        for (int i = 0; i < PRIME_ORIGINAL.size(); i++) {
            nodes.add(node("p-a" + i, PRIME_ORIGINAL.get(i)));
        }
        nodes.add(node("p-5", "SmartPC Builder | Example University | Jan 2025 - Mar 2025"));
        for (int i = 0; i < SMART_ORIGINAL.size(); i++) {
            nodes.add(node("p-b" + i, SMART_ORIGINAL.get(i)));
        }
        return nodes;
    }

    private static List<V3WritableNode> writable(String prefix, List<String> bullets) {
        List<V3WritableNode> nodes = new ArrayList<>();
        for (int i = 0; i < bullets.size(); i++) {
            nodes.add(new V3WritableNode(prefix + i, bullets.get(i)));
        }
        return nodes;
    }

    private static V3EntryRewriteRequest request(
            V3EntryFactGraph graph,
            List<V3WritableNode> writable,
            V3ResumeFactGraphs graphs) {
        return new V3EntryRewriteRequest(
                V3ProductMode.BASIC,
                graph.canonicalEntryKey(),
                graph.sectionRole(),
                graph.originalHeading(),
                graph.originalDateText(),
                V3BasicGoldenTest.JD,
                List.of(),
                List.of(),
                writable,
                graph,
                graphs,
                List.of(),
                List.of(),
                writable.size(),
                800,
                "");
    }

    private static V3BasicRunResult.V3EntryTrace trace(V3BasicRunResult result, String key) {
        return result.traces().stream()
                .filter(item -> key.equals(item.canonicalEntryKey()))
                .findFirst()
                .orElseThrow();
    }
}
