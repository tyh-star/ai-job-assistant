package aijob.util;

import aijob.dto.PdfLayout;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeTextBlock;
import aijob.enums.ResumeExtractionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfLayoutExtractorTest {

    private final PdfLayoutExtractor extractor = new PdfLayoutExtractor();
    private final ResumeStructureDraftBuilder draftBuilder = new ResumeStructureDraftBuilder();

    @TempDir
    Path tempDir;

    @Test
    void yaohangTangPdf_summaryHeadingIsNotLocation_andContactIsPrefillOnly() throws Exception {
        Path pdf = fixture("resumes/yaohang-tang.pdf");
        PdfLayout layout = extractor.extract(pdf);
        ResumeStructure structure = draftBuilder.build(layout);

        assertEquals(ResumeExtractionStatus.LAYOUT.name(), layout.getStatus());
        assertFalse(layout.getBlocks().isEmpty());
        assertTrue(unassignedText(structure).contains("SUMMARY OF QUALIFICATIONS"), unassignedText(structure));
        assertFalse(containsIgnoreCase(structure.getContact().getLocation(), "QUALIFICATIONS"));
        assertFalse(containsIgnoreCase(structure.getContact().getFullName(), "QUALIFICATIONS"));
        assertTrue(containsIgnoreCase(structure.getContact().getFullName(), "Tang")
                || containsIgnoreCase(structure.getContact().getFullName(), "Yaohang"));
        assertTrue(structure.getContact().getEmail().contains("@"));
        assertFalse(structure.getContact().getPhone().isBlank());
        assertFalse(structure.getContact().getLinks().isEmpty());
        assertTrue(structure.getSkills().isEmpty());
        assertTrue(structure.getExperience().isEmpty());
        assertTrue(structure.getProjects().isEmpty());
        assertEquals("", structure.getSummary());
        assertEquals(ResumeTextBlock.PROVENANCE_RULE, structure.getFieldProvenance().get("contact.email"));
    }

    @Test
    void weirdHeadings_areKeptAsUnassignedBlocks() throws Exception {
        Path pdf = tempDir.resolve("weird.pdf");
        PdfLayoutTestDocuments.writeWeirdHeadingResume(pdf);

        PdfLayout layout = extractor.extract(pdf);
        ResumeStructure structure = draftBuilder.build(layout);
        String unassigned = unassignedText(structure);

        assertEquals("Alex Rivera", structure.getContact().getFullName());
        assertEquals("alex.rivera@example.com", structure.getContact().getEmail());
        assertTrue(unassigned.contains("SUMMARY OF QUALIFICATIONS"), unassigned);
        assertTrue(unassigned.contains("CORE COMPETENCIES"), unassigned);
        assertTrue(unassigned.contains("RELEVANT PROJECTS"), unassigned);
        assertTrue(unassigned.contains("Job Tracker"), unassigned);
        assertTrue(unassigned.contains("Java, MySQL, Redis"), unassigned);
        assertTrue(structure.getSkills().isEmpty());
        assertTrue(structure.getProjects().isEmpty());
        assertFalse(containsIgnoreCase(structure.getContact().getLocation(), "SUMMARY"));
        assertTrue(idsAreStable(layout.getBlocks()));
    }

    @Test
    void twoColumnFixture_hasStableReadingOrderAndBlockIds() throws Exception {
        Path pdf = tempDir.resolve("two-col.pdf");
        PdfLayoutTestDocuments.writeTwoColumnResume(pdf);

        PdfLayout first = extractor.extract(pdf);
        PdfLayout second = extractor.extract(pdf);
        List<String> firstIds = first.getBlocks().stream().map(ResumeTextBlock::getId).toList();
        List<String> secondIds = second.getBlocks().stream().map(ResumeTextBlock::getId).toList();
        List<String> firstTexts = first.getBlocks().stream().map(ResumeTextBlock::getText).toList();
        List<String> secondTexts = second.getBlocks().stream().map(ResumeTextBlock::getText).toList();

        assertEquals(firstIds, secondIds);
        assertEquals(firstTexts, secondTexts);
        assertTrue(idsAreStable(first.getBlocks()));

        String joined = String.join("\n", firstTexts);
        assertTrue(joined.contains("Jane Doe"), joined);
        assertTrue(joined.contains("SKILLS"), joined);
        assertTrue(joined.contains("EXPERIENCE"), joined);
        int skillsAt = indexContaining(firstTexts, "SKILLS");
        int javaAt = indexContaining(firstTexts, "Java");
        int experienceAt = indexContaining(firstTexts, "EXPERIENCE");
        int acmeAt = indexContaining(firstTexts, "Acme");
        assertTrue(skillsAt >= 0 && javaAt > skillsAt, joined);
        assertTrue(experienceAt > javaAt, joined);
        assertTrue(acmeAt > experienceAt, joined);

        ResumeStructure structure = draftBuilder.build(first);
        assertEquals("Jane Doe", structure.getContact().getFullName());
        assertEquals("jane.doe@example.com", structure.getContact().getEmail());
        assertTrue(structure.getContact().getPhone().contains("555"));
        assertTrue(structure.getContact().getLinks().stream().anyMatch(link -> link.contains("linkedin.com")));
        assertTrue(structure.getSkills().isEmpty());
        assertTrue(structure.getExperience().isEmpty());
    }

    @Test
    void emptyPdf_isImageOnlyWithEmptyUnassigned() throws Exception {
        Path pdf = tempDir.resolve("empty.pdf");
        PdfLayoutTestDocuments.writeEmptyPage(pdf);

        PdfLayout layout = extractor.extract(pdf);
        ResumeStructure structure = draftBuilder.build(layout);

        assertEquals(ResumeExtractionStatus.IMAGE_ONLY.name(), layout.getStatus());
        assertTrue(layout.getBlocks().isEmpty());
        assertEquals(ResumeExtractionStatus.IMAGE_ONLY.name(), structure.getExtractionStatus());
        assertTrue(structure.getUnassigned().isEmpty());
        assertEquals("", structure.getContact().getFullName());
        assertEquals("", structure.getContact().getEmail());
    }

    private Path fixture(String classpath) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(getClass().getClassLoader().getResource(classpath)).toURI());
    }

    private static String unassignedText(ResumeStructure structure) {
        return structure.getUnassigned().stream()
                .map(ResumeTextBlock::getText)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toUpperCase().contains(needle.toUpperCase());
    }

    private static boolean idsAreStable(List<ResumeTextBlock> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
            ResumeTextBlock block = blocks.get(i);
            if (block.getId() == null || !block.getId().startsWith("b-p")) {
                return false;
            }
            if (block.getPage() < 1 || block.getText().isBlank()) {
                return false;
            }
            if (block.getFeatures() == null || block.getConfidence() == null) {
                return false;
            }
        }
        return true;
    }

    private static int indexContaining(List<String> texts, String needle) {
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
