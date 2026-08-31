package aijob.tailor.v3;

import aijob.util.ClaimLedger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3EntryQualityScorerTest {

    @Test
    void originalBeatsSynonymSwapAndAwkwardNominalization() {
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ClaimLedger.from(primeAndSmart()));
        V3EntryFactGraph prime = graphs.entry("PROJECT:prime-deals");
        List<V3RankedRequirement> requirements = V3JdRequirementRanker.rank(V3BasicGoldenTest.JD, prime);
        V3QualityScore original = V3EntryQualityScorer.scoreOriginal(
                prime, prime.originalBulletTexts(), requirements);
        V3QualityScore executed = V3EntryQualityScorer.scoreVariant(
                prime,
                prime.originalBulletTexts(),
                List.of(
                        prime.originalBulletTexts().get(0),
                        prime.originalBulletTexts().get(1),
                        "Executed the deployment of the project's initial version to AWS."
                ),
                requirements);
        assertTrue(executed.languageRegression() || !V3EntryQualityScorer.admit(executed, original), String.valueOf(executed));

        V3EntryFactGraph smart = graphs.entry("PROJECT:smartpc-builder");
        List<String> originalSmart = List.of(
                "Developed a Java Maven project that helps users select optimal components within a given budget",
                "Communicated effectively with TA and Professor to clarify requirements."
        );
        V3QualityScore originalComm = V3EntryQualityScorer.scoreOriginal(smart, originalSmart, requirements);
        V3QualityScore engaged = V3EntryQualityScorer.scoreVariant(
                smart,
                originalSmart,
                List.of(originalSmart.get(0), "Engaged with TA and Professor to clarify requirements."),
                requirements);
        assertTrue(engaged.languageRegression() || !V3EntryQualityScorer.admit(engaged, originalComm), String.valueOf(engaged));
    }

    @Test
    void coordinatedPrimeRewriteCanBeatOriginal() {
        V3EntryFactGraph prime = V3EntryFactGraphFactory.from(ClaimLedger.from(primeAndSmart()))
                .entry("PROJECT:prime-deals");
        List<V3RankedRequirement> requirements = V3JdRequirementRanker.rank(V3BasicGoldenTest.JD, prime);
        V3QualityScore original = V3EntryQualityScorer.scoreOriginal(
                prime, prime.originalBulletTexts(), requirements);
        V3QualityScore variant = V3EntryQualityScorer.scoreVariant(
                prime,
                prime.originalBulletTexts(),
                List.of(
                        "Designed and implemented the application in Java Spring Boot using MVC.",
                        "Integrated RESTful APIs to support user authentication, product updates, and shopping cart operations.",
                        "Deployed the initial version of the project to AWS."
                ),
                requirements);
        assertFalse(variant.languageRegression(), String.valueOf(variant));
        assertTrue(variant.total() >= 75, String.valueOf(variant));
        assertTrue(V3EntryQualityScorer.admit(variant, original), original + " vs " + variant);
    }
}
