package aijob.tailor.v3;

import aijob.dto.ConfirmTailorProposalRequest;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.TailorProposalState;
import aijob.dto.V3EvidenceUsage;
import aijob.dto.V3SkillReview;
import aijob.util.ClaimLedger;
import aijob.util.TermAliasNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3EnhancedEvidenceTraceabilityTest {

    private static final String PRIME = "PROJECT:prime-deals";
    private static final String SMART = "PROJECT:smartpc-builder";
    private static final String JD = V3BasicLiveQualityAcceptanceTest.JD_INTERN
            + " Docker Kubernetes";

    @Test
    void unusedPrimeEvidenceStaysVisibleWithReason() {
        V3UserEvidence prime = confirmed(11L, PRIME, "maintain",
                "maintained the AWS application with weekly patching");
        V3UserEvidence metric = confirmed(12L, SMART, "metric",
                "Processed 10,000 combinations in under 500 ms");
        V3EnhancedRunResult result = run(withSkills("Java, Python, SQL"), List.of(prime, metric), new SmartMetricOnly());
        V3EvidenceUsage unused = usage(result, 11L);
        V3EvidenceUsage used = usage(result, 12L);
        assertNotNull(unused);
        assertNotNull(used);
        assertEquals(V3EvidenceUsage.SAVED_NOT_USED, unused.getUsageStatus());
        assertEquals(V3EvidenceUsage.NO_ADMITTED_REWRITE, unused.getReasonCode());
        assertTrue(result.entryReviews().stream().noneMatch(review -> PRIME.equals(review.getCanonicalEntryKey())));
        assertTrue(result.entryReviews().stream().anyMatch(review -> SMART.equals(review.getCanonicalEntryKey())));
        assertEquals(V3EvidenceUsage.USED_IN_ENTRY, used.getUsageStatus());
        assertTrue(used.getUsedInSkillKeys() == null || used.getUsedInSkillKeys().isEmpty());
        assertTrue(result.evidenceUsage().stream().anyMatch(item ->
                Long.valueOf(11L).equals(item.getEvidenceItemId())));
        assertTrue(result.entryReviews().stream().noneMatch(review ->
                review.getWhyBetter() != null && review.getWhyBetter().toLowerCase(Locale.ROOT).endsWith("metric.")));
        assertTrue(result.skillReviews().stream().noneMatch(review ->
                review.getWhyBetter() != null && review.getWhyBetter().toLowerCase(Locale.ROOT).contains("metric")));
    }

    @Test
    void mysqlCanEnterPrimeAndSkillsWhileMetricDoesNotEnterSkills() {
        List<ResumeDocumentNode> nodes = withSkills("Java, Python, SQL");
        V3UserEvidence mysql = confirmed(21L, PRIME, "data-store",
                "Used MySQL to store product and cart data in Prime Deals");
        V3UserEvidence metric = confirmed(22L, SMART, "metric",
                "Processed 10,000 combinations in under 500 ms");
        V3EnhancedRunResult result = run(nodes, List.of(mysql, metric), new Scripted());
        V3EvidenceUsage mysqlUsage = usage(result, 21L);
        V3EvidenceUsage metricUsage = usage(result, 22L);
        assertTrue(mysqlUsage.getUsedInSkillKeys().contains("MySQL")
                || mysqlUsage.getUsageStatus().contains("SKILLS")
                || mysqlUsage.getUsageStatus().contains("ENTRY"), mysqlUsage.getUsageStatus());
        assertTrue(result.skillReviews().stream().anyMatch(review ->
                review.getAddedSkills().contains("MySQL")));
        assertTrue(result.skillReviews().get(0).getWhyBetter().toLowerCase(Locale.ROOT).contains("mysql"));
        assertFalse(result.skillReviews().get(0).getWhyBetter().toLowerCase(Locale.ROOT).contains("data-store"));
        assertFalse(result.skillReviews().get(0).getWhyBetter().toLowerCase(Locale.ROOT).contains("metric"));
        assertTrue(metricUsage.getUsedInSkillKeys() == null || metricUsage.getUsedInSkillKeys().isEmpty());
        String skillsText = result.skillReviews().isEmpty()
                ? ""
                : result.skillReviews().get(0).getProposedSkills().toLowerCase(Locale.ROOT);
        assertFalse(skillsText.contains("10,000") || skillsText.contains("500"));
        assertFalse(skillsText.contains("docker"));
        assertFalse(skillsText.contains("kubernetes"));
    }

    @Test
    void unconfirmedDockerIsNeverAddedToSkills() {
        V3SkillsProjector.Result projected = V3SkillsProjector.project(
                withSkills("Java, Python, SQL"),
                List.of(confirmed(31L, PRIME, "data-store",
                        "Used MySQL to store product and cart data in Prime Deals")));
        String proposed = projected.reviews().isEmpty() ? "" : projected.reviews().get(0).getProposedSkills();
        assertTrue(proposed.contains("MySQL"));
        assertFalse(proposed.toLowerCase(Locale.ROOT).contains("docker"));
        assertFalse(proposed.toLowerCase(Locale.ROOT).contains("kubernetes"));
    }

    @Test
    void aliasDedupesAndKeepsMysqlDistinctFromSql() {
        assertTrue(TermAliasNormalizer.sameTerm("REST", "RESTful API"));
        assertTrue(TermAliasNormalizer.sameTerm("OOP", "Object-Oriented Programming"));
        assertTrue(TermAliasNormalizer.sameTerm("java doc string", "Javadoc"));
        assertFalse(TermAliasNormalizer.sameTerm("Java", "JavaScript"));
        assertFalse(TermAliasNormalizer.sameTerm("Spring", "Spring Boot"));
        assertFalse(TermAliasNormalizer.sameTerm("MySQL", "SQL"));
        V3SkillsProjector.Result rest = V3SkillsProjector.project(
                withSkills("Java, REST API, OOP"),
                List.of(confirmed(41L, PRIME, "testing",
                        "Used RESTful APIs and Object-Oriented Programming and Java doc string")));
        if (!rest.reviews().isEmpty()) {
            assertFalse(rest.reviews().get(0).getAddedSkills().contains("REST"));
            assertFalse(rest.reviews().get(0).getAddedSkills().contains("OOP"));
            assertTrue(rest.reviews().get(0).getAddedSkills().contains("Javadoc")
                    || rest.reviews().get(0).getProposedSkills().contains("Javadoc"));
        }
        V3SkillsProjector.Result existing = V3SkillsProjector.project(
                withSkills("Java, Python, C/C++, SQL, HTML, Vue, Javascript, CSS, MySQL"),
                List.of(confirmed(42L, PRIME, "data-store",
                        "Used MySQL to store product and cart data in Prime Deals")));
        assertTrue(existing.reviews().isEmpty());
        assertTrue(existing.changes().isEmpty());
    }

    @Test
    void missingSkillsSectionDoesNotCreateAChapter() {
        V3UserEvidence mysql = confirmed(51L, PRIME, "data-store",
                "Used MySQL to store product and cart data in Prime Deals");
        V3EnhancedRunResult result = run(
                primeAndSmart(),
                List.of(mysql),
                request -> V3EntryRewriteResult.noChange(request.canonicalEntryKey()));
        V3EvidenceUsage usage = usage(result, 51L);
        assertTrue(result.skillReviews().isEmpty());
        assertTrue(result.changes().stream().noneMatch(change ->
                change.getNewText() != null && change.getNewText().startsWith("TECHNICAL SKILLS")));
        assertEquals(V3EvidenceUsage.SAVED_NOT_USED, usage.getUsageStatus());
        assertEquals(V3EvidenceUsage.NO_SKILLS_SECTION, usage.getReasonCode());
    }

    @Test
    void deletedEvidenceIsAbsentFromFactGraph() {
        ClaimLedger ledger = ClaimLedger.from(withSkills("Java, Python, SQL"));
        V3UserEvidence mysql = confirmed(61L, PRIME, "data-store",
                "Used MySQL to store product and cart data in Prime Deals");
        V3ResumeFactGraphs with = V3EntryFactGraphFactory.from(ledger, List.of(mysql));
        assertTrue(with.entry(PRIME).factsOf(V3FactKind.USER_CONFIRMED).stream()
                .anyMatch(fact -> fact.evidenceItemId() != null && fact.evidenceItemId() == 61L));
        V3ResumeFactGraphs without = V3EntryFactGraphFactory.from(ledger, List.of());
        assertTrue(without.entry(PRIME).factsOf(V3FactKind.USER_CONFIRMED).stream()
                .noneMatch(fact -> Long.valueOf(61L).equals(fact.evidenceItemId())));
    }

    @Test
    void oldProposalArrayAndEnvelopeStillParse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TailorChange change = new TailorChange();
        change.setNodeId("p-4");
        TailorProposalState array = TailorProposalState.parse(mapper, mapper.writeValueAsString(List.of(change)));
        assertEquals("p-4", array.getChanges().get(0).getNodeId());
        TailorProposalState stored = TailorProposalState.fromChanges(List.of(change), List.of("p-4"));
        stored.setProductMode("ENHANCED");
        V3EvidenceUsage usage = new V3EvidenceUsage();
        usage.setEvidenceItemId(11L);
        usage.setUsageStatus(V3EvidenceUsage.SAVED_NOT_USED);
        usage.setReasonCode(V3EvidenceUsage.NO_ADMITTED_REWRITE);
        stored.setEvidenceUsage(List.of(usage));
        V3SkillReview review = new V3SkillReview();
        review.setProposedSkills("Java, MySQL");
        review.setAddedSkills(List.of("MySQL"));
        stored.setSkillReviews(List.of(review));
        TailorProposalState parsed = TailorProposalState.parse(mapper, mapper.writeValueAsString(stored));
        assertEquals(V3EvidenceUsage.SAVED_NOT_USED, parsed.getEvidenceUsage().get(0).getUsageStatus());
        assertEquals("MySQL", parsed.getSkillReviews().get(0).getAddedSkills().get(0));
        ConfirmTailorProposalRequest legacy = mapper.readValue("{\"selectedNodeIds\":[\"p-4\"]}", ConfirmTailorProposalRequest.class);
        assertEquals(List.of("p-4"), legacy.getSelectedNodeIds());
        ConfirmTailorProposalRequest skillsOnly = mapper.readValue(
                "{\"selectedSkillReviewIds\":[\"skills:technical\"]}", ConfirmTailorProposalRequest.class);
        assertEquals(List.of("skills:technical"), skillsOnly.getSelectedSkillReviewIds());
    }

    private static V3EnhancedRunResult run(
            List<ResumeDocumentNode> nodes,
            List<V3UserEvidence> evidence,
            V3EnhancedEntryRewriteGenerator generator) {
        V3OpportunityMap map = new V3EnhancedOpportunityService().scan(nodes, JD, evidence);
        return new V3EnhancedTailorService(generator, V3RewriteTestSupport.acceptBestAi())
                .run(nodes, JD, evidence, map);
    }

    private static List<ResumeDocumentNode> withSkills(String skills) {
        List<ResumeDocumentNode> nodes = new ArrayList<>();
        nodes.add(new ResumeDocumentNode("p-s0", "PARAGRAPH", "TECHNICAL SKILLS"));
        nodes.add(new ResumeDocumentNode("p-s1", "PARAGRAPH", skills));
        nodes.addAll(primeAndSmart());
        return nodes;
    }

    private static V3UserEvidence confirmed(Long id, String entry, String topic, String action) {
        return new V3UserEvidence(id, entry, "CONFIRMED_USED", action, "", "", "", topic, topic);
    }

    private static V3EvidenceUsage usage(V3EnhancedRunResult result, long id) {
        return result.evidenceUsage().stream()
                .filter(item -> Long.valueOf(id).equals(item.getEvidenceItemId()))
                .findFirst()
                .orElse(null);
    }

    private static final class SmartMetricOnly implements V3EnhancedEntryRewriteGenerator {
        @Override
        public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
            if (!SMART.equals(request.canonicalEntryKey())) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            List<String> bullets = new ArrayList<>();
            for (V3WritableNode node : request.writableNodes()) {
                String text = node.originalText();
                if (request.userConfirmedFacts().stream().anyMatch(item ->
                        item.displayText().toLowerCase(Locale.ROOT).contains("500"))
                        && (text.toLowerCase(Locale.ROOT).contains("algorithm")
                        || text.toLowerCase(Locale.ROOT).contains("component"))) {
                    text = text + " Processed 10,000 combinations in under 500 ms.";
                }
                bullets.add(text);
            }
            return V3RewriteTestSupport.rewriteBoth(request, bullets.toArray(String[]::new));
        }
    }

    private static final class Scripted implements V3EnhancedEntryRewriteGenerator {
        @Override
        public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
            List<String> bullets = new ArrayList<>();
            for (V3WritableNode node : request.writableNodes()) {
                String text = node.originalText();
                if (SMART.equals(request.canonicalEntryKey())
                        && request.userConfirmedFacts().stream().anyMatch(item ->
                        item.displayText().toLowerCase(Locale.ROOT).contains("500"))) {
                    if (text.toLowerCase(Locale.ROOT).contains("algorithm")
                            || text.toLowerCase(Locale.ROOT).contains("component")) {
                        text = text + " Processed 10,000 combinations in under 500 ms.";
                    }
                }
                if (PRIME.equals(request.canonicalEntryKey())
                        && request.userConfirmedFacts().stream().anyMatch(item ->
                        item.displayText().toLowerCase(Locale.ROOT).contains("mysql"))) {
                    if (text.toLowerCase(Locale.ROOT).contains("java")) {
                        text = text.replace("MVC pattern.", "MVC pattern and MySQL for product and cart data.");
                    }
                }
                bullets.add(text);
            }
            return V3RewriteTestSupport.rewriteBoth(request, bullets.toArray(String[]::new));
        }
    }
}
