package aijob.util;

import aijob.dto.EntryOption;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog keys for the synthetic Alex Morgan fixture. {@code PROJECT:prime-deals}
 * is the fictional Prime Deals project, not a real product. */
class ResumeEntryCatalogTest {

    @Test
    void docxClaimLedgerProducesStableProjectKeys() {
        List<EntryOption> options = ResumeEntryCatalog.options(
                ResumeRequirementMatcherTest.alexMorganNodes(),
                null
        );
        List<String> keys = options.stream().map(EntryOption::getEntryKey).toList();
        assertTrue(keys.contains("PROJECT:prime-deals"));
        assertTrue(keys.contains("PROJECT:smartpc"));
        assertTrue(keys.contains("GENERAL"));
        EntryOption prime = options.stream()
                .filter(option -> "PROJECT:prime-deals".equals(option.getEntryKey()))
                .findFirst()
                .orElseThrow();
        assertTrue(prime.getNodeIds().contains("p-13"));
        assertTrue(prime.getNodeIds().contains("p-16"));
        assertEquals("Prime Deals", prime.getEntryLabel());
    }

    @Test
    void pdfStructuredJsonProducesExperienceAndProjectKeys() {
        ResumeStructure structure = new ResumeStructure();
        ResumeStructure.ResumeProject project = new ResumeStructure.ResumeProject();
        project.setName("Prime Deals");
        project.setSourceBlockIds(List.of("b-1", "b-2"));
        ResumeStructure.ResumeExperience experience = new ResumeStructure.ResumeExperience();
        experience.setCompany("Acme");
        experience.setTitle("Backend Intern");
        experience.setSourceBlockIds(List.of("b-9"));
        structure.setProjects(List.of(project));
        structure.setExperience(List.of(experience));
        structure.normalize();

        List<EntryOption> options = ResumeEntryCatalog.options(List.of(
                new ResumeDocumentNode("t-0", "TEXT", "ignored line")
        ), structure);

        List<String> keys = options.stream().map(EntryOption::getEntryKey).toList();
        assertTrue(keys.contains("PROJECT:prime-deals"));
        assertTrue(keys.contains("EXPERIENCE:backend-intern-acme"));
        assertTrue(keys.contains("GENERAL"));
    }
}
