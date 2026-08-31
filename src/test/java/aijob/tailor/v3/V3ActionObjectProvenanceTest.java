package aijob.tailor.v3;

import aijob.dto.AnswerStatus;
import aijob.dto.ResumeDocumentNode;
import aijob.util.ClaimLedger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.node;
import static aijob.tailor.v3.V3MaterialityHybridTest.PRIME_CLARITY;
import static aijob.tailor.v3.V3MaterialityHybridTest.PRIME_KEY;
import static aijob.tailor.v3.V3MaterialityHybridTest.PRIME_ORIGINAL;
import static aijob.tailor.v3.V3MaterialityHybridTest.SMART_KEY;
import static aijob.tailor.v3.V3MaterialityHybridTest.fullPrimeAndSmart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3ActionObjectProvenanceTest {

    private static final List<String> E02_FINAL = List.of(
            "Led and collaborated with a partner to develop the project's architecture and application.",
            "Defined project scope and clarified requirements using UML class diagrams for effective planning.",
            "Designed and implemented a shopping website using Java and Spring Boot, following the MVC pattern.",
            "Built and integrated RESTful APIs for authentication, product updates, and cart management.",
            "Deployed the initial version of the application to AWS."
    );

    private static final List<String> E05_FINAL = List.of(
            "Led and collaborated with a friend to develop a shopping website.",
            "Defined project scope, clarified requirements, and used UML class diagrams for planning.",
            "Designed and implemented the application using Java and Spring Boot with an MVC pattern.",
            "Integrated RESTful APIs for user authentication, product updates, and shopping cart management.",
            "Deployed and maintained the application on AWS."
    );

    @Test
    void originalPrimeRelationsStayOnSourceBullets() {
        V3EntryFactGraph prime = graphs().entry(PRIME_KEY);
        List<V3ActionObjectRelation> relations = V3ActionObjectProvenanceGate.relationsFrom(prime);
        assertTrue(relations.stream().anyMatch(item ->
                "develop".equals(item.action()) && "shopping_website".equals(item.objectKey())));
        assertTrue(relations.stream().anyMatch(item ->
                ("design".equals(item.action()) || "implement".equals(item.action()))
                        && "application".equals(item.objectKey())));
        assertTrue(relations.stream().anyMatch(item ->
                "integrate".equals(item.action()) && "rest_apis".equals(item.objectKey())));
        assertTrue(relations.stream().anyMatch(item ->
                "deploy".equals(item.action()) && "aws".equals(item.objectKey())));
        assertFalse(relations.stream().anyMatch(item ->
                "build".equals(item.action()) && "rest_apis".equals(item.objectKey())));
        assertFalse(relations.stream().anyMatch(item ->
                "maintain".equals(item.action())));
        assertTrue(relations.stream().anyMatch(item -> item.sourceNodeId().startsWith("p-a")));
    }

    @Test
    void rejectsBuiltAndIntegratedWhenOriginalOnlyIntegrated() {
        V3ValidationDecision decision = validate(replace(PRIME_ORIGINAL, 3,
                "Built and integrated RESTful APIs for authentication, product updates, and cart management."));
        assertEquals(V3ValidationDecision.INTRODUCED_RELATION, decision.reasonCode(), decision.detail());
        assertTrue(decision.detail().toLowerCase(Locale.ROOT).contains("build"), decision.detail());
    }

    @Test
    void rejectsDeployedAndMaintainedWhenOriginalOnlyDeployed() {
        V3ValidationDecision decision = validate(replace(PRIME_ORIGINAL, 4,
                "Deployed and maintained the application on AWS."));
        assertEquals(V3ValidationDecision.INTRODUCED_RELATION, decision.reasonCode(), decision.detail());
        assertTrue(decision.detail().toLowerCase(Locale.ROOT).contains("maintain"), decision.detail());
        assertTrue(V3ActionObjectProvenanceGate.hasMaintainHallucination(
                graphs().entry(PRIME_KEY), replace(PRIME_ORIGINAL, 4,
                        "Deployed and maintained the application on AWS.")));
    }

    @Test
    void rejectsFriendToTeammateOrPartnerButAllowsDropOrKeep() {
        V3ValidationDecision teammate = validate(replace(PRIME_ORIGINAL, 0,
                "Led development and collaboration with a teammate to create a shopping website."));
        assertEquals(V3ValidationDecision.OPTIONAL_CONTEXT_MUTATION, teammate.reasonCode(), teammate.detail());

        V3ValidationDecision partner = validate(replace(PRIME_ORIGINAL, 0,
                "Led and collaborated with a partner to develop a shopping website."));
        assertEquals(V3ValidationDecision.OPTIONAL_CONTEXT_MUTATION, partner.reasonCode(), partner.detail());

        V3ValidationDecision keep = validate(replace(PRIME_ORIGINAL, 0,
                "Led and collaborated with a friend to develop a shopping website."));
        assertTrue(keep.accepted(), keep.reasonCode() + " " + keep.detail());

        V3ValidationDecision drop = validate(replace(PRIME_ORIGINAL, 0,
                "Led and collaborated on developing a shopping website."));
        assertTrue(drop.accepted(), drop.reasonCode() + " " + drop.detail());
    }

    @Test
    void allowsDesignBackendServicesFromApplicationAndRestEvidence() {
        V3ValidationDecision decision = validate(replace(PRIME_ORIGINAL, 2,
                "Designed and implemented backend services using Java and Spring Boot with an MVC pattern."));
        assertTrue(decision.accepted(), decision.reasonCode() + " " + decision.detail());
    }

    @Test
    void allowsBuiltApisWhenOriginalAlreadyBuiltThem() {
        List<ResumeDocumentNode> nodes = List.of(
                node("p-0", "RELEVANT PROJECTS"),
                node("p-1", "Checkout | 2024"),
                node("p-2", "Built RESTful APIs for checkout.")
        );
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ClaimLedger.from(nodes));
        String key = V3CanonicalEntryKey.of(ClaimLedger.from(nodes).entryOf("p-2"));
        V3ValidationDecision decision = V3EntryRewriteValidator.validate(
                graphs,
                new V3EntryRewrite(key, "", "", List.of("Built RESTful APIs to handle checkout.")));
        assertTrue(decision.accepted(), decision.reasonCode() + " " + decision.detail());
    }

    @Test
    void userConfirmedMaintainAllowedOnEnhancedGraphButNotInventedInBasic() {
        V3ValidationDecision basic = validate(replace(PRIME_ORIGINAL, 4,
                "Deployed and maintained the application on AWS."));
        assertFalse(basic.accepted());

        V3UserEvidence maintained = new V3UserEvidence(
                31L, PRIME_KEY, AnswerStatus.CONFIRMED_USED,
                "maintained", "applications on AWS", "", "", "ops");
        V3ResumeFactGraphs enhanced = V3EntryFactGraphFactory.from(
                ClaimLedger.from(fullPrimeAndSmart()), List.of(maintained));
        V3ValidationDecision allowed = V3EntryRewriteValidator.validate(
                enhanced,
                new V3EntryRewrite(PRIME_KEY, "", "", replace(PRIME_ORIGINAL, 4,
                        "Deployed and maintained the application on AWS.")));
        assertTrue(allowed.accepted(), allowed.reasonCode() + " " + allowed.detail());
    }

    @Test
    void e02AndE05FinalsAreRejected() {
        V3ValidationDecision e02 = validate(E02_FINAL);
        assertFalse(e02.accepted(), "E02 FINAL must fail provenance");
        assertTrue(V3ValidationDecision.OPTIONAL_CONTEXT_MUTATION.equals(e02.reasonCode())
                        || V3ValidationDecision.INTRODUCED_RELATION.equals(e02.reasonCode()),
                e02.reasonCode() + " " + e02.detail());

        V3ValidationDecision e05 = validate(E05_FINAL);
        assertEquals(V3ValidationDecision.INTRODUCED_RELATION, e05.reasonCode(), e05.detail());
        assertTrue(e05.detail().toLowerCase(Locale.ROOT).contains("maintain"), e05.detail());
    }

    @Test
    void previouslyAcceptedPrimeBulletsStillPass() {
        List<IndexedBullet> good = goodIsolatedBullets();
        assertTrue(good.size() >= 37, "expected at least 37 previously accepted bullets, was " + good.size());
        for (IndexedBullet item : good) {
            V3ValidationDecision decision = validate(replace(PRIME_ORIGINAL, item.index, item.text));
            assertTrue(decision.accepted(), item.text + " -> " + decision.reasonCode() + " " + decision.detail());
        }
    }

    @Test
    void reviewerAndHybridCannotRestoreRelationRejectedVariant() {
        List<String> targeted = new ArrayList<>(PRIME_CLARITY);
        targeted.set(3, "Built and integrated RESTful APIs for authentication, product updates, and cart management.");
        V3ResumeLanguageReviewer reviewer = (jobDescription, entries) -> {
            List<V3ResumeLanguageReviewer.Choice> choices = new ArrayList<>();
            for (V3ResumeLanguageReviewer.EntryComparison entry : entries) {
                choices.add(new V3ResumeLanguageReviewer.Choice(
                        entry.canonicalEntryKey(), V3SelectionChoice.JD_TARGETED, "force-built"));
            }
            return choices;
        };
        V3BasicRunResult result = new V3BasicTailorService(request -> {
            if (!PRIME_KEY.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            return V3RewriteTestSupport.rewriteSplit(request, PRIME_CLARITY, targeted);
        }, reviewer).run(fullPrimeAndSmart(), V3BasicGoldenTest.JD);
        V3BasicRunResult.V3EntryTrace prime = result.traces().stream()
                .filter(item -> PRIME_KEY.equals(item.canonicalEntryKey()))
                .findFirst()
                .orElseThrow();
        String finals = String.join(" ", prime.finalBullets()).toLowerCase(Locale.ROOT);
        assertFalse(finals.contains("built and integrated"), prime.finalBullets().toString());
        assertTrue(prime.chosenVariant() != V3SelectionChoice.JD_TARGETED
                || !prime.targetedBullets().get(3).toLowerCase(Locale.ROOT).contains("built and integrated"));
        V3ValidationDecision decision = validate(prime.finalBullets());
        assertTrue(decision.accepted(), decision.reasonCode() + " " + decision.detail());
        assertFalse(result.traces().stream()
                .filter(item -> SMART_KEY.equals(item.canonicalEntryKey()))
                .findFirst()
                .orElseThrow()
                .finalBullets()
                .stream()
                .anyMatch(bullet -> bullet.toLowerCase(Locale.ROOT).contains("stakeholder")));
    }

    @Test
    void professorAndTaCannotBecomeStakeholderOrClient() {
        V3ValidationDecision stakeholder = V3EntryRewriteValidator.validate(
                graphs(),
                new V3EntryRewrite(SMART_KEY, "", "", List.of(
                        "Developed a Java Maven project that helps users select optimal components within a given budget",
                        "Applied Object-Oriented Programming (OOP) principles, leveraging inheritance and encapsulation for modularity",
                        "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)",
                        "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality",
                        "Communicated effectively with TA and Professor and stakeholders to clarify project requirements and resolve ambiguities"
                )));
        assertEquals(V3ValidationDecision.OPTIONAL_CONTEXT_MUTATION, stakeholder.reasonCode(), stakeholder.detail());
    }

    private static List<IndexedBullet> goodIsolatedBullets() {
        // 40 rewritten Prime bullets from V3-2.3 live-9, minus the 3 leak classes
        // (built+integrated, deployed+maintained, friend→teammate/partner), plus
        // the two friend-kept restorations of those substituted lead bullets.
        List<IndexedBullet> good = new ArrayList<>();
        good.add(new IndexedBullet(0, "Led and collaborated with a friend to develop a shopping website."));
        good.add(new IndexedBullet(0, "Led and collaborated with a friend to develop a shopping website."));
        good.add(new IndexedBullet(0, "Led collaboration with a friend to develop a shopping website."));
        good.add(new IndexedBullet(0, "Led and collaborated with a friend to develop a shopping website."));
        good.add(new IndexedBullet(0, "Led and collaborated with a friend to develop a shopping website."));
        good.add(new IndexedBullet(0, "Led and collaborated on developing a shopping website."));
        good.add(new IndexedBullet(0, "Led development and collaboration with a friend to create a shopping website."));
        good.add(new IndexedBullet(1, "Defined project scope, clarified requirements, and used UML class diagrams for planning."));
        good.add(new IndexedBullet(1, "Defined project scope and clarified requirements using UML class diagrams for effective planning."));
        good.add(new IndexedBullet(1, "Defined project scope, clarified requirements, and used UML class diagrams for planning."));
        good.add(new IndexedBullet(1, "Defined project scope, clarified requirements, and used UML class diagrams for planning."));
        good.add(new IndexedBullet(1, "Defined project scope, clarified requirements, and used UML class diagrams for planning."));
        good.add(new IndexedBullet(1, "Defined project scope and clarified requirements using UML class diagrams for planning."));
        good.add(new IndexedBullet(1, "Defined project scope and clarified requirements using UML class diagrams for planning."));
        good.add(new IndexedBullet(1, "Defined project scope, clarified requirements, and used UML class diagrams for planning."));
        good.add(new IndexedBullet(2, "Designed and implemented the application using Java and Spring Boot with an MVC pattern."));
        good.add(new IndexedBullet(2, "Designed and implemented a shopping website using Java and Spring Boot, following the MVC pattern."));
        good.add(new IndexedBullet(2, "Designed and implemented the application using Java and Spring Boot with an MVC pattern."));
        good.add(new IndexedBullet(2, "Designed and implemented backend services using Java and Spring Boot with an MVC pattern."));
        good.add(new IndexedBullet(2, "Designed and implemented the application using Java and Spring Boot with an MVC pattern."));
        good.add(new IndexedBullet(2, "Designed and implemented the application using Java and Spring Boot with an MVC pattern."));
        good.add(new IndexedBullet(2, "Designed and implemented the application using Java and Spring Boot with an MVC pattern."));
        good.add(new IndexedBullet(2, "Designed and implemented the application using Java and Spring Boot following an MVC pattern."));
        good.add(new IndexedBullet(3, "Integrated RESTful APIs to support user authentication, product updates, and shopping cart functions."));
        good.add(new IndexedBullet(3, "Integrated RESTful APIs for user authentication, product updates, and cart management."));
        good.add(new IndexedBullet(3, "Integrated RESTful APIs to handle user authentication, product updates, and shopping cart management."));
        good.add(new IndexedBullet(3, "Integrated RESTful APIs to handle user authentication, product updates, and shopping cart management."));
        good.add(new IndexedBullet(3, "Integrated RESTful APIs for user authentication, product updates, and shopping cart management."));
        good.add(new IndexedBullet(3, "Integrated RESTful APIs for user authentication, product updates, and shopping cart management."));
        good.add(new IndexedBullet(3, "Integrated RESTful APIs for user authentication, product updates, and shopping cart management."));
        good.add(new IndexedBullet(3, "Integrated RESTful APIs for user authentication, product updates, and shopping cart management."));
        good.add(new IndexedBullet(4, "Deployed the initial version of the project to AWS."));
        good.add(new IndexedBullet(4, "Deployed the initial version of the application to AWS."));
        good.add(new IndexedBullet(4, "Deployed the initial version of the project to AWS."));
        good.add(new IndexedBullet(4, "Deployed the initial version of the project to AWS."));
        good.add(new IndexedBullet(4, "Deployed the initial version of the project to AWS."));
        good.add(new IndexedBullet(4, "Deployed the initial version of the application to AWS."));
        return good;
    }

    private static V3ValidationDecision validate(List<String> bullets) {
        return V3EntryRewriteValidator.validate(graphs(), new V3EntryRewrite(PRIME_KEY, "", "", bullets));
    }

    private static List<String> replace(List<String> original, int index, String bullet) {
        List<String> next = new ArrayList<>(original);
        next.set(index, bullet);
        return next;
    }

    private static V3ResumeFactGraphs graphs() {
        return V3EntryFactGraphFactory.from(ClaimLedger.from(fullPrimeAndSmart()));
    }

    private record IndexedBullet(int index, String text) {
    }
}
