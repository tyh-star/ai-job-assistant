package aijob.tailor.v3;

import aijob.dto.AnswerStatus;
import aijob.util.ClaimLedger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3EnhancedAtomicCoverageTest {

    private static final String PRIME = "PROJECT:prime-deals";
    private static final String SMART = "PROJECT:smartpc-builder";
    private static final String DEPLOY_MAINTAIN = "Deploy and maintain applications on AWS";
    private static final String JAVA_SPRING = "Java and Spring Boot";

    @Test
    void deployAndAwsWithoutMaintainIsPartiallyCovered() {
        V3EntryFactGraph prime = V3EntryFactGraphFactory.from(ClaimLedger.from(primeAndSmart())).entry(PRIME);
        assertEquals(V3RequirementCoverage.PARTIALLY_COVERED,
                V3EnhancedAtomicCoverage.coverage(DEPLOY_MAINTAIN, prime));
        assertTrue(V3EnhancedAtomicCoverage.missingAtoms(DEPLOY_MAINTAIN, prime).stream()
                .anyMatch(atom -> "action:maintain".equals(atom.id())));
    }

    @Test
    void confirmedMaintainMakesDeployMaintainCovered() {
        V3UserEvidence confirmed = new V3UserEvidence(
                41L, PRIME, AnswerStatus.CONFIRMED_USED,
                "maintained the AWS deployment with weekly patching", "", "", "", "maintain");
        V3EntryFactGraph prime = V3EntryFactGraphFactory.from(
                ClaimLedger.from(primeAndSmart()), List.of(confirmed)).entry(PRIME);
        assertEquals(V3RequirementCoverage.COVERED,
                V3EnhancedAtomicCoverage.coverage(DEPLOY_MAINTAIN, prime));
        V3OpportunityMap map = new V3EnhancedOpportunityService().scan(
                primeAndSmart(), V3BasicLiveQualityAcceptanceTest.JD_INTERN, List.of(confirmed));
        V3OpportunityEntry row = map.entries().stream()
                .filter(entry -> PRIME.equals(entry.canonicalEntryKey()))
                .findFirst()
                .orElseThrow();
        assertTrue(row.coveredRequirements().stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT).contains("deploy")
                        && item.toLowerCase(Locale.ROOT).contains("maintain")));
        assertEquals(0, map.questions().stream().filter(question ->
                PRIME.equals(question.canonicalEntryKey()) && "maintain".equals(question.topic())).count());
    }

    @Test
    void smartPcDoesNotInheritPrimeSpringRestAws() {
        V3OpportunityMap map = new V3EnhancedOpportunityService().scan(
                primeAndSmart(), V3BasicLiveQualityAcceptanceTest.JD_INTERN);
        V3OpportunityEntry smart = map.entries().stream()
                .filter(entry -> SMART.equals(entry.canonicalEntryKey()))
                .findFirst()
                .orElseThrow();
        assertFalse(containsIgnoreCase(smart.canStrengthenWithExistingFacts(), "spring boot"));
        assertFalse(containsIgnoreCase(smart.canStrengthenWithExistingFacts(), "restful"));
        assertFalse(containsIgnoreCase(smart.canStrengthenWithExistingFacts(), "aws"));
        assertFalse(containsIgnoreCase(smart.coveredRequirements(), "spring boot"));
        assertFalse(containsIgnoreCase(smart.coveredRequirements(), "restful"));
        assertFalse(containsIgnoreCase(smart.coveredRequirements(), "aws"));
    }

    @Test
    void javaWithoutSpringBootIsPartiallyCoveredOnSmartPc() {
        V3EntryFactGraph smart = V3EntryFactGraphFactory.from(ClaimLedger.from(primeAndSmart())).entry(SMART);
        assertEquals(V3RequirementCoverage.PARTIALLY_COVERED,
                V3EnhancedAtomicCoverage.coverage(JAVA_SPRING, smart));
        V3EntryFactGraph prime = V3EntryFactGraphFactory.from(ClaimLedger.from(primeAndSmart())).entry(PRIME);
        assertEquals(V3RequirementCoverage.COVERED,
                V3EnhancedAtomicCoverage.coverage(JAVA_SPRING, prime));
    }

    @Test
    void javascriptContractNxAndKafkaDoNotFalseCover() {
        V3EntryFactGraph prime = V3EntryFactGraphFactory.from(ClaimLedger.from(primeAndSmart())).entry(PRIME);
        String skills = "Java, Python, C/C++, SQL, HTML, Vue, Javascript, CSS, MySQL";
        String jsContract = "JavaScript skills and API/event contract design";
        String nx = "Practical experience with NX monorepos (shared libs, dependency graph, affected builds/tests)";
        String kafka = "Experience with event-driven systems (Kafka or equivalent), including retries, idempotency, and schema/version evolution";
        assertEquals(V3RequirementCoverage.PARTIALLY_COVERED,
                V3EnhancedAtomicCoverage.coverage(jsContract, prime, skills));
        V3JdRequirementBreakdown first = V3EnhancedAtomicCoverage.breakdown(jsContract, prime, skills);
        assertTrue(first.coveredAtoms().stream().anyMatch(item -> item.equalsIgnoreCase("JavaScript")));
        assertTrue(first.missingAtoms().stream().anyMatch(item -> item.toLowerCase(Locale.ROOT).contains("contract")));
        assertEquals(V3JdRequirementBreakdown.PARTIALLY_COVERED, first.status());
        assertEquals(V3RequirementCoverage.UNSUPPORTED, V3EnhancedAtomicCoverage.coverage(nx, prime, skills));
        assertEquals(V3JdRequirementBreakdown.MISSING, V3EnhancedAtomicCoverage.breakdown(nx, prime, skills).status());
        assertEquals(V3RequirementCoverage.UNSUPPORTED, V3EnhancedAtomicCoverage.coverage(kafka, prime, skills));
        assertEquals(V3RequirementCoverage.UNSUPPORTED, V3EnhancedAtomicCoverage.coverage(jsContract, prime, ""));
        assertEquals(V3RequirementCoverage.UNSUPPORTED,
                V3EnhancedAtomicCoverage.coverage("NestJS", prime, skills));
        assertFalse(V3EnhancedAtomicCoverage.localEvidence(nx, prime));
        String mavenEvidence = "Maven dependency management and ordinary unit testing of REST APIs";
        assertFalse(V3EnhancedAtomicCoverage.atoms(nx).stream().anyMatch(atom ->
                atom.presentIn(mavenEvidence, prime, "")));
        assertFalse(V3EnhancedAtomicCoverage.atoms(kafka).stream().anyMatch(atom ->
                atom.presentIn("Integrated RESTful APIs", prime, "")));
        assertFalse(V3EnhancedAtomicCoverage.atoms(jsContract).stream().anyMatch(atom ->
                "tech:javascript".equals(atom.id()) && atom.presentIn("Java Spring Boot", prime, "")));
    }

    private static boolean containsIgnoreCase(List<String> values, String needle) {
        String match = needle.toLowerCase(Locale.ROOT);
        return values.stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(match));
    }
}
