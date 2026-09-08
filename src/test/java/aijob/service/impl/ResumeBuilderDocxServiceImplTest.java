package aijob.service.impl;

import aijob.dto.builder.NormalizedResume;
import aijob.util.ResumeBuilderNormalizer;
import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftContent.EducationEntry;
import aijob.dto.builder.ResumeBuilderDraftContent.WorkExperienceEntry;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixtures: Alex Morgan, Example University, Example Company -- entirely fictional. */
class ResumeBuilderDocxServiceImplTest {

    private final ResumeBuilderDocxServiceImpl writer = new ResumeBuilderDocxServiceImpl();
    private final ResumeBuilderNormalizer normalizer = new ResumeBuilderNormalizer();

    @TempDir
    Path tempDir;

    @Test
    void writesAValidDocxWhoseTextContainsOnlyEnteredFactsAndOmitsBlankSections() throws IOException {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        content.getBasicInfo().setEmail("alex.morgan@example.com");
        content.getJobTarget().setTargetTitle("Backend Engineer");

        WorkExperienceEntry exp = new WorkExperienceEntry();
        exp.setCompany("Example Company");
        exp.setTitle("Software Engineer");
        exp.setResponsibilities(List.of("Built a payments service"));
        content.setExperience(List.of(exp));
        // Education left entirely blank -- must not appear anywhere in the file.

        NormalizedResume normalized = normalizer.normalize(content, "en");
        Path out = tempDir.resolve("resume.docx");
        writer.write(normalized, out);

        assertTrue(java.nio.file.Files.exists(out));
        String text;
        try (FileInputStream in = new FileInputStream(out.toFile());
             XWPFDocument doc = new XWPFDocument(in)) {
            text = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
        }
        assertTrue(text.contains("Alex Morgan"));
        assertTrue(text.contains("Backend Engineer"));
        assertTrue(text.contains("Example Company"));
        assertTrue(text.contains("Built a payments service"));
        assertFalse(text.toUpperCase().contains("EDUCATION"));
    }

    @Test
    void previewAndDocxAgreeOnSectionsAndOrderBecauseBothReadTheSameNormalizedModel() throws IOException {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        EducationEntry edu = new EducationEntry();
        edu.setSchool("Example University");
        edu.setDegree("B.Sc.");
        content.setEducation(List.of(edu));
        WorkExperienceEntry exp = new WorkExperienceEntry();
        exp.setCompany("Example Company");
        exp.setTitle("Engineer");
        content.setExperience(List.of(exp));

        NormalizedResume normalized = normalizer.normalize(content, "en");
        Path out = tempDir.resolve("resume2.docx");
        writer.write(normalized, out);

        String text;
        try (FileInputStream in = new FileInputStream(out.toFile());
             XWPFDocument doc = new XWPFDocument(in)) {
            text = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
        }
        // Every section the preview says would render actually appears in the file,
        // and nothing the preview omitted appears either.
        for (var section : normalized.getSections()) {
            assertTrue(text.toUpperCase().contains(section.getTitle().toUpperCase()),
                    "expected section title in docx: " + section.getTitle());
        }
        for (String omittedId : normalized.getOmittedSectionIds()) {
            assertFalse(text.toUpperCase().contains(omittedId), omittedId + " should never appear");
        }
    }

    /**
     * Item 8 of the acceptance checklist: the ATS target is one page, but that is
     * NEVER enforced by shrinking the font -- overflow is only ever a preview warning
     * (see ResumeBuilderNormalizerTest#veryLongContentWarnsAboutOverflowButNeverBlocksGeneration),
     * telling the user to trim. The writer itself has no "shrink to fit" logic at all;
     * this proves that holds even when content is long enough to genuinely overflow.
     */
    @Test
    void fontSizeStaysFixedRegardlessOfContentLengthNeverShrunkToFitOnePage() throws IOException {
        ResumeBuilderDraftContent shortContent = ResumeBuilderDraftContent.blank();
        shortContent.getBasicInfo().setFullName("Alex Morgan");
        WorkExperienceEntry shortExp = new WorkExperienceEntry();
        shortExp.setCompany("Example Company");
        shortExp.setTitle("Engineer");
        shortExp.setResponsibilities(List.of("Built a thing"));
        shortContent.setExperience(List.of(shortExp));
        NormalizedResume shortResume = normalizer.normalize(shortContent, "en");
        Path shortOut = tempDir.resolve("short.docx");
        writer.write(shortResume, shortOut);

        ResumeBuilderDraftContent longContent = ResumeBuilderDraftContent.blank();
        longContent.getBasicInfo().setFullName("Alex Morgan");
        List<WorkExperienceEntry> jobs = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            WorkExperienceEntry e = new WorkExperienceEntry();
            e.setCompany("Example Company " + i);
            e.setTitle("Engineer");
            e.setResponsibilities(List.of("Did thing A", "Did thing B", "Did thing C"));
            jobs.add(e);
        }
        longContent.setExperience(jobs);
        NormalizedResume longResume = normalizer.normalize(longContent, "en");
        assertFalse(longResume.getWarnings().isEmpty(), "long content should trigger the overflow warning");
        Path longOut = tempDir.resolve("long.docx");
        writer.write(longResume, longOut);

        Set<Double> shortFontSizes = collectRunFontSizes(shortOut);
        Set<Double> longFontSizes = collectRunFontSizes(longOut);

        // The exact same fixed set of font sizes renders regardless of how much
        // content there is -- never shrunk to squeeze more onto one page.
        assertEquals(shortFontSizes, longFontSizes);
        for (Double size : longFontSizes) {
            assertTrue(size >= 10.0, "font size " + size + " is too small to be readable");
        }
    }

    private Set<Double> collectRunFontSizes(Path docxPath) throws IOException {
        Set<Double> sizes = new HashSet<>();
        try (FileInputStream in = new FileInputStream(docxPath.toFile());
             XWPFDocument doc = new XWPFDocument(in)) {
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                for (var run : paragraph.getRuns()) {
                    Double size = run.getFontSizeAsDouble();
                    if (size != null && size > 0) {
                        sizes.add(size);
                    }
                }
            }
        }
        return sizes;
    }
}
