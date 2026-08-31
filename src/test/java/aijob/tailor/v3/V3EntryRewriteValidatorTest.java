package aijob.tailor.v3;

import aijob.dto.AnswerStatus;
import aijob.util.ClaimLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3EntryRewriteValidatorTest {

    private V3ResumeFactGraphs graphs;
    private V3EntryFactGraph prime;

    @BeforeEach
    void load() {
        ClaimLedger ledger = ClaimLedger.from(primeAndSmart());
        V3UserEvidence confirmed = new V3UserEvidence(
                21L, "PROJECT:prime-deals", AnswerStatus.CONFIRMED_USED,
                "", "handled 200 requests per second", "200", "", "load");
        V3UserEvidence rejected = new V3UserEvidence(
                22L, "PROJECT:prime-deals", AnswerStatus.CONFIRMED_NOT_USED,
                "", "", "", "", "Kubernetes");
        graphs = V3EntryFactGraphFactory.from(ledger, List.of(confirmed, rejected));
        prime = graphs.entry("PROJECT:prime-deals");
    }

    @Test
    void allowsSameEntryReorderProfessionalWordingAndGrammar() {
        assertAccepted(rewrite(List.of(
                "Deployed the initial version of the project to AWS.",
                "Integrated RESTful APIs to support user authentication, product updates, and shopping cart operations.",
                "Designed and implemented the application in Java Spring Boot using MVC."
        )));
        assertAccepted(rewrite(List.of(
                "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements",
                "Successfully deployed the initial version of the project onto AWS",
                "Design and implement the application using Java Spring Boot with an MVC pattern."
        )));
        assertAccepted(rewrite(List.of(
                "Integrated RESTful APIs to manage shopping cart operations, user authentication, and product updates.",
                "Deployed the initial version of the project to AWS.",
                "Design and implement the application using Java Spring Boot with an MVC pattern."
        )));
    }

    @Test
    void rejectsCrossEntryInventedMetricLeadershipLowValueAndDeniedFacts() {
        V3ValidationDecision cross = V3EntryRewriteValidator.validate(graphs, rewrite(List.of(
                "Design and implement the application using Java Spring Boot with an MVC pattern.",
                "Developed a Java Maven project that helps users select optimal components.",
                "Successfully deployed the initial version of the project onto AWS"
        )));
        assertEquals(V3ValidationDecision.CROSS_ENTRY_FACT, cross.reasonCode());

        V3ValidationDecision docker = V3EntryRewriteValidator.validate(graphs, rewrite(List.of(
                "Design and implement the application using Java Spring Boot and Docker.",
                prime.originalBulletTexts().get(1),
                prime.originalBulletTexts().get(2)
        )));
        assertEquals(V3ValidationDecision.INVENTED_SKILL, docker.reasonCode());

        V3ValidationDecision degree = V3EntryRewriteValidator.validate(graphs, rewrite(List.of(
                "Earned a Bachelor degree while building Java Spring Boot.",
                prime.originalBulletTexts().get(1),
                prime.originalBulletTexts().get(2)
        )));
        assertEquals(V3ValidationDecision.INVENTED_CREDENTIAL, degree.reasonCode());

        V3ValidationDecision metric = V3EntryRewriteValidator.validate(graphs, rewrite(List.of(
                prime.originalBulletTexts().get(0),
                "Integrated RESTful APIs and improved conversion by 40%.",
                prime.originalBulletTexts().get(2)
        )));
        assertEquals(V3ValidationDecision.UNCONFIRMED_METRIC, metric.reasonCode());

        V3ValidationDecision confirmedMetric = V3EntryRewriteValidator.validate(graphs, rewrite(List.of(
                prime.originalBulletTexts().get(0),
                "Integrated RESTful APIs that handled 200 requests per second for user authentication, product updates, and shopping cart operations.",
                prime.originalBulletTexts().get(2)
        )));
        assertTrue(confirmedMetric.accepted(), confirmedMetric.detail());

        V3ValidationDecision denied = V3EntryRewriteValidator.validate(graphs, rewrite(List.of(
                "Design and implement the application using Java Spring Boot and Kubernetes.",
                prime.originalBulletTexts().get(1),
                prime.originalBulletTexts().get(2)
        )));
        assertEquals(V3ValidationDecision.USER_REJECTED_FACT, denied.reasonCode());

        V3ValidationDecision led = V3EntryRewriteValidator.validate(graphs, rewrite(List.of(
                "Led and designed and implemented the application using Java Spring Boot with an MVC pattern.",
                prime.originalBulletTexts().get(1),
                prime.originalBulletTexts().get(2)
        )));
        assertEquals(V3ValidationDecision.ROLE_ESCALATION, led.reasonCode());

        V3ValidationDecision executed = V3EntryRewriteValidator.validate(graphs, rewrite(List.of(
                prime.originalBulletTexts().get(0),
                prime.originalBulletTexts().get(1),
                "Executed the deployment of the project's initial version to AWS."
        )));
        assertTrue(executed.accepted(), executed.reasonCode() + " " + executed.detail());

        V3ValidationDecision engage = V3EntryRewriteValidator.validate(
                graphsForCommunication(),
                new V3EntryRewrite(
                        "PROJECT:smartpc-builder",
                        "",
                        "",
                        List.of(
                                "Developed a Java Maven project that helps users select optimal components within a given budget",
                                "● Engaged with TA and Professor to clarify requirements."
                        )));
        assertTrue(engage.accepted(), engage.reasonCode() + " " + engage.detail());

        V3ValidationDecision heading = V3EntryRewriteValidator.validate(graphs, new V3EntryRewrite(
                "PROJECT:prime-deals",
                "Prime Deals Lead Engineer",
                prime.originalDateText(),
                prime.originalBulletTexts()
        ));
        assertEquals(V3ValidationDecision.IDENTITY_MUTATION, heading.reasonCode());

        V3ValidationDecision dates = V3EntryRewriteValidator.validate(graphs, new V3EntryRewrite(
                "PROJECT:prime-deals",
                prime.originalHeading(),
                "Jan 2020 - Dec 2020",
                prime.originalBulletTexts()
        ));
        assertEquals(V3ValidationDecision.IDENTITY_MUTATION, dates.reasonCode());
    }

    @Test
    void rejectsMissingAndDuplicateMaterialFacts() {
        V3ValidationDecision missing = V3EntryRewriteValidator.validate(graphs, rewrite(List.of(
                "Designed and implemented the application in Java Spring Boot using MVC.",
                "Integrated RESTful APIs to support user authentication, product updates, and shopping cart operations.",
                "Deployed the initial version of the project."
        )));
        assertEquals(V3ValidationDecision.MISSING_MATERIAL_FACT, missing.reasonCode());

        V3ValidationDecision duplicate = V3EntryRewriteValidator.validate(graphs, rewrite(List.of(
                "Designed and implemented the application in Java Spring Boot using MVC.",
                "Integrated Java RESTful APIs to support user authentication, product updates, and shopping cart operations.",
                "Deployed the initial version of the project to AWS."
        )));
        assertEquals(V3ValidationDecision.DUPLICATE_MATERIAL_FACT, duplicate.reasonCode());
    }

    private void assertAccepted(V3EntryRewrite rewrite) {
        V3ValidationDecision decision = V3EntryRewriteValidator.validate(graphs, rewrite);
        assertTrue(decision.accepted(), decision.reasonCode() + " " + decision.detail());
    }

    private V3EntryRewrite rewrite(List<String> bullets) {
        return new V3EntryRewrite("PROJECT:prime-deals", "", "", bullets);
    }

    private static V3ResumeFactGraphs graphsForCommunication() {
        ClaimLedger ledger = ClaimLedger.from(List.of(
                V3CanonicalEntryKeyTest.node("p-0", "RELEVANT PROJECTS"),
                V3CanonicalEntryKeyTest.node("p-5", "SmartPC Builder | York University | Jan 2025 - Mar 2025"),
                V3CanonicalEntryKeyTest.node("p-6", "Developed a Java Maven project that helps users select optimal components within a given budget"),
                V3CanonicalEntryKeyTest.node("p-7", "● Communicated effectively with TA and Professor to clarify requirements.")
        ));
        return V3EntryFactGraphFactory.from(ledger);
    }
}
