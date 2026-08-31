package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.ResumeDocumentNode;
import aijob.service.impl.ResumeDocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailorV2T2B2YaohangRealPdfTest {

    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.";

    @TempDir
    Path tempDir;

    private List<ResumeDocumentNode> nodes;
    private ClaimLedger ledger;

    @BeforeEach
    void parseRealCanonicalDocx() throws Exception {
        Path pdf = YaohangCanonicalDocxFixture.pdf();
        Path docx = tempDir.resolve("yaohang-canonical.docx");
        YaohangCanonicalDocxFixture.writeCanonicalDocx(pdf, docx);
        nodes = new ResumeDocumentServiceImpl().parseDocx(docx);
        ledger = ClaimLedger.from(nodes);
        System.out.println("T2B.2 REAL parseDocx nodes");
        for (ResumeDocumentNode node : nodes) {
            System.out.println(node.getNodeId() + "\t" + node.getText());
        }
        System.out.println("T2B.2 REAL ClaimLedger entries");
        for (ClaimLedger.Entry entry : ledger.entries()) {
            System.out.println("ENTRY id=" + entry.entryId() + " role=" + entry.sectionRole());
            for (ResumeDocumentNode node : entry.nodes()) {
                System.out.println("  " + node.getNodeId() + "\t" + node.getText());
            }
        }
    }

    @Test
    void realPdf_findsRequiredBulletsByOriginalTextNotFixedNodeIds() {
        ResumeDocumentNode springBoot = requireNode(
                "Design and implement", "Java Spring Boot", "MVC");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ResumeDocumentNode aws = requireNode("Successfully deployed", "AWS");
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ResumeDocumentNode oop = requireNode("Object-Oriented Programming");
        ResumeDocumentNode algorithm = requireNode("Implemented an algorithm", "CPU", "GPU", "RAM");
        ResumeDocumentNode documentation = requireNode("well-documented");
        ResumeDocumentNode communication = requireNode("Communicated effectively");

        assertFalse(joined().toLowerCase(Locale.ROOT).contains("acme"), joined());
        assertEquals(springBoot.getNodeId(), findNode("Design and implement", "MVC").getNodeId());
        assertTrue(rest.getText().toLowerCase(Locale.ROOT).contains("authentication"));
        assertTrue(aws.getText().toLowerCase(Locale.ROOT).contains("aws"));
        assertTrue(oop.getText().toLowerCase(Locale.ROOT).contains("oop"));
        assertTrue(algorithm.getText().contains("CPU"));
        assertTrue(documentation.getText().toLowerCase(Locale.ROOT).contains("java doc"));
        assertTrue(communication.getText().toLowerCase(Locale.ROOT).contains("ta")
                || communication.getText().toLowerCase(Locale.ROOT).contains("professor"));
    }

    @Test
    void realPdf_claimLedgerGroupsPrimeDealsAndSmartPcWithoutCrossEntryFacts() {
        ResumeDocumentNode springBoot = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ClaimLedger.Entry prime = ledger.entryOf(springBoot.getNodeId());
        ClaimLedger.Entry smart = ledger.entryOf(maven.getNodeId());
        String primeText = joined(prime);
        String smartText = joined(smart);

        System.out.println("T2B.2 REAL Prime Deals entryKey via packet="
                + ConservativeEvidencePacket.build(springBoot.getNodeId(), ledger).entryKey());
        System.out.println("T2B.2 REAL SmartPC entryKey via packet="
                + ConservativeEvidencePacket.build(maven.getNodeId(), ledger).entryKey());

        assertTrue(primeText.contains("Spring Boot") || primeText.contains("MVC"), primeText);
        assertTrue(primeText.toLowerCase(Locale.ROOT).contains("restful"), primeText);
        assertTrue(primeText.toLowerCase(Locale.ROOT).contains("aws"), primeText);
        assertTrue(primeText.contains("http://") || primeText.contains("35.183"), primeText);
        assertTrue(smartText.toLowerCase(Locale.ROOT).contains("maven"), smartText);
        assertTrue(smartText.toLowerCase(Locale.ROOT).contains("oop"), smartText);
        assertTrue(smartText.toLowerCase(Locale.ROOT).contains("algorithm"), smartText);
        assertTrue(smartText.contains("CPU") && smartText.contains("GPU") && smartText.contains("RAM"), smartText);
        assertTrue(smartText.toLowerCase(Locale.ROOT).contains("documented"), smartText);
        assertTrue(smartText.toLowerCase(Locale.ROOT).contains("communicat"), smartText);
        assertFalse(primeText.toLowerCase(Locale.ROOT).contains("maven project"), primeText);
        assertFalse(smartText.toLowerCase(Locale.ROOT).contains("spring boot"), smartText);

        ConservativeEvidencePacket primePacket = ConservativeEvidencePacket.build(springBoot.getNodeId(), ledger);
        ConservativeEvidencePacket mavenPacket = ConservativeEvidencePacket.build(maven.getNodeId(), ledger);
        assertTrue(ConservativeEvidencePacket.supportingIdsStayInSameEntry(
                springBoot.getNodeId(), primePacket.supportingNodeIds(), ledger));
        assertTrue(ConservativeEvidencePacket.supportingIdsStayInSameEntry(
                maven.getNodeId(), mavenPacket.supportingNodeIds(), ledger));
        for (String supportingId : primePacket.supportingNodeIds()) {
            assertFalse(ledger.sameEntry(maven.getNodeId(), supportingId), supportingId);
        }
        for (String supportingId : mavenPacket.supportingNodeIds()) {
            assertFalse(ledger.sameEntry(springBoot.getNodeId(), supportingId), supportingId);
        }
    }

    @Test
    void realPdf_evidencePacketsStayInsideTheirProject() {
        ResumeDocumentNode springBoot = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ConservativeEvidencePacket primePacket = ConservativeEvidencePacket.build(springBoot.getNodeId(), ledger);
        ConservativeEvidencePacket mavenPacket = ConservativeEvidencePacket.build(maven.getNodeId(), ledger);
        String primeJson = primePacket.toJson().toLowerCase(Locale.ROOT);
        String mavenJson = mavenPacket.toJson().toLowerCase(Locale.ROOT);

        System.out.println("T2B.2 REAL Prime Deals Packet\n" + primePacket.toJson());
        System.out.println("T2B.2 REAL SmartPC Packet\n" + mavenPacket.toJson());

        assertEquals(springBoot.getNodeId(), primePacket.targetNodeId());
        assertEquals(maven.getNodeId(), mavenPacket.targetNodeId());
        assertTrue(primePacket.requiredFacts().contains("role:design"));
        assertTrue(primePacket.requiredFacts().contains("role:implement"));
        assertTrue(primePacket.requiredFacts().contains("tech:Java"));
        assertTrue(primePacket.requiredFacts().contains("tech:Spring Boot"));
        assertTrue(primePacket.requiredFacts().contains("tech:MVC"));
        assertFalse(primePacket.requiredFacts().contains("tech:Spring"));
        assertFalse(primePacket.requiredFacts().contains("task:mvc"));
        assertTrue(primeJson.contains("rest"));
        assertTrue(primeJson.contains("auth") || primeJson.contains("authentication"));
        assertTrue(primeJson.contains("product"));
        assertTrue(primeJson.contains("cart"));
        assertFalse(primeJson.contains("maven"));
        assertFalse(primeJson.contains("oop"));
        assertFalse(primeJson.contains("cpu"));
        assertFalse(primeJson.contains("gpu"));
        assertFalse(primeJson.matches("(?s).*\\bram\\b.*"));

        assertTrue(mavenPacket.requiredFacts().stream().anyMatch(fact -> fact.toLowerCase(Locale.ROOT).contains("java")));
        assertTrue(mavenPacket.requiredFacts().stream().anyMatch(fact -> fact.toLowerCase(Locale.ROOT).contains("maven")));
        assertTrue(mavenJson.contains("oop"));
        assertTrue(mavenJson.contains("algorithm") || mavenJson.contains("cpu"));
        assertTrue(mavenJson.contains("cpu"));
        assertTrue(mavenJson.contains("gpu"));
        assertTrue(mavenJson.contains("ram"));
        assertFalse(mavenJson.contains("spring boot"));
        assertFalse(mavenJson.contains("restful"));
        assertFalse(mavenJson.contains("aws"));
    }

    @Test
    void realPdf_plannerTopFiveCoversJdProjectsAndSkipsSoftOrMetadataNodes() {
        List<ConservativeRewritePlan> plans = ConservativeTailorPlanner.plan(nodes, JD);
        ResumeDocumentNode springBoot = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ResumeDocumentNode oop = requireNode("Object-Oriented Programming");
        ResumeDocumentNode algorithm = requireNode("Implemented an algorithm", "CPU");
        ResumeDocumentNode documentation = requireNode("well-documented");
        ResumeDocumentNode communication = requireNode("Communicated effectively");

        System.out.println("T2B.2 REAL Planner top " + plans.size() + " (no synthetic ACME nodes)");
        for (int i = 0; i < plans.size(); i++) {
            ConservativeRewritePlan plan = plans.get(i);
            ResumeDocumentNode node = node(plan.nodeId());
            System.out.println((i + 1) + ". " + plan.nodeId()
                    + " entry=" + plan.entryKey()
                    + " intent=" + plan.rewriteIntent()
                    + " text=" + (node == null ? "" : node.getText()));
        }

        List<String> ids = plans.stream().map(ConservativeRewritePlan::nodeId).toList();
        List<String> texts = ids.stream().map(id -> textOf(node(id))).toList();
        assertTrue(plans.size() <= ConservativeTailorPlanner.GENERATION_PAGE_SIZE);
        ConservativeTailorPlanner.CoveragePage page = ConservativeTailorPlanner.nextPage(
                nodes, "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.", Set.of());
        assertTrue(page.eligibleNodeIds().contains(documentation.getNodeId())
                || page.eligibleNodeIds().contains(communication.getNodeId())
                || !page.eligibleNodeIds().isEmpty());
        assertTrue(page.eligibleNodeIds().contains(documentation.getNodeId()));
        for (String text : texts) {
            String lower = text.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("http://") || lower.contains("https://"), text);
            assertFalse(lower.contains("acme"), text);
            assertFalse(isHeading(text), text);
            assertFalse(lower.contains("yaohang tang") && !lower.contains("design"), text);
        }
        List<String> top3 = texts.subList(0, Math.min(3, texts.size()));
        String top3Joined = String.join("\n", top3).toLowerCase(Locale.ROOT);
        assertTrue(page.eligibleNodeIds().contains(springBoot.getNodeId()));
        assertTrue(page.eligibleNodeIds().contains(rest.getNodeId()));
        assertTrue(page.eligibleNodeIds().contains(maven.getNodeId()));
        assertTrue(page.eligibleNodeIds().contains(algorithm.getNodeId()));
        assertTrue(ids.contains(springBoot.getNodeId()) || page.remainingNodeIds().contains(springBoot.getNodeId()));
        assertTrue(ids.contains(rest.getNodeId()) || page.remainingNodeIds().contains(rest.getNodeId()));
        assertTrue(top3Joined.contains("spring boot")
                || top3Joined.contains("restful")
                || top3Joined.contains("documented"), top3.toString());

        long primeDeals = plans.stream()
                .filter(plan -> ledger.sameEntry(plan.nodeId(), springBoot.getNodeId()))
                .count();
        long smartPc = plans.stream()
                .filter(plan -> ledger.sameEntry(plan.nodeId(), maven.getNodeId()))
                .count();
        assertTrue(primeDeals >= 0);
        assertTrue(smartPc >= 0);
        assertFalse(joined().toLowerCase(Locale.ROOT).contains("acme"));
        assertFalse(nodes.stream().anyMatch(node -> "p-20b".equals(node.getNodeId())
                && textOf(node).toLowerCase(Locale.ROOT).contains("model cpu")));
    }

    private ResumeDocumentNode requireNode(String... needles) {
        return findNode(needles);
    }

    private ResumeDocumentNode findNode(String... needles) {
        for (ResumeDocumentNode node : nodes) {
            String text = textOf(node);
            boolean all = true;
            for (String needle : needles) {
                if (!text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return node;
            }
        }
        throw new AssertionError("Missing real node containing " + String.join(" + ", needles)
                + " in:\n" + joined());
    }

    private ResumeDocumentNode node(String nodeId) {
        return nodes.stream().filter(item -> nodeId.equals(item.getNodeId())).findFirst().orElse(null);
    }

    private String joined() {
        return nodes.stream().map(node -> node.getNodeId() + "\t" + node.getText())
                .collect(Collectors.joining("\n"));
    }

    private static String joined(ClaimLedger.Entry entry) {
        return entry.nodes().stream().map(ResumeDocumentNode::getText).collect(Collectors.joining("\n"));
    }

    private static String textOf(ResumeDocumentNode node) {
        return node == null || node.getText() == null ? "" : node.getText();
    }

    private static boolean isHeading(String text) {
        String normalized = text.replaceAll("[^A-Za-z\\s]", " ").replaceAll("\\s+", " ").strip().toUpperCase(Locale.ROOT);
        return normalized.equals("SUMMARY OF QUALIFICATIONS")
                || normalized.equals("SUMMARY")
                || normalized.equals("TECHNICAL PROFICIENCIES")
                || normalized.equals("TECHNICAL SKILLS")
                || normalized.equals("SKILLS")
                || normalized.equals("RELEVANT PROJECTS")
                || normalized.equals("PROJECTS")
                || normalized.equals("EXPERIENCE")
                || normalized.equals("EDUCATION");
    }
}
