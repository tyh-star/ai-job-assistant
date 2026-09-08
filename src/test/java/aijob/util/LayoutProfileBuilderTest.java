package aijob.util;

import aijob.dto.LayoutProfile;
import aijob.dto.PdfLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutProfileBuilderTest {

    private final PdfLayoutExtractor extractor = new PdfLayoutExtractor();

    @TempDir
    Path tempDir;

    @Test
    void generatedResumePdf_extractsLeftRuleUnderlineGroupedRightDates() throws Exception {
        PdfLayout layout = extractor.extract(GeneratedResumePdfFixture.pdf());
        LayoutProfile profile = LayoutProfileBuilder.from(layout);

        assertEquals(LayoutProfile.PAGE_LETTER, profile.getPageSize());
        assertEquals(LayoutProfile.ALIGN_LEFT, profile.getHeaderAlignment());
        assertEquals(LayoutProfile.ALIGN_LEFT, profile.getContactAlignment());
        assertTrue(profile.isHasHeaderRule(), "expected a header rule on the generated resume fixture");
        assertTrue(profile.getSectionHeading().isAllCaps());
        assertTrue(profile.getSectionHeading().isUnderline(), "expected underlined section headings");
        assertEquals(LayoutProfile.SKILL_GROUPED, profile.getSectionLayout().getSkillPresentation());
        assertEquals(
                LayoutProfile.DATE_RIGHT,
                profile.getSectionLayout().getProjectHeaderDateAlignment()
        );
        assertNotEquals(LayoutProfile.CONFIDENCE_LOW, profile.getConfidence());
        String bodyFont = (profile.getBody().getFontFamily() + " " + profile.getBaseFontFamily()).toLowerCase();
        assertTrue(bodyFont.contains("times") || bodyFont.contains("roman"), bodyFont);
    }

    @Test
    void centeredSansFixture_isNotAlexMorganStyle() throws Exception {
        Path pdf = tempDir.resolve("centered-sans.pdf");
        PdfLayoutTestDocuments.writeCenteredSansResume(pdf);

        LayoutProfile profile = LayoutProfileBuilder.from(extractor.extract(pdf));

        assertEquals(LayoutProfile.ALIGN_CENTER, profile.getHeaderAlignment());
        assertEquals(LayoutProfile.ALIGN_CENTER, profile.getContactAlignment());
        assertFalse(profile.isHasHeaderRule());
        assertFalse(profile.getSectionHeading().isUnderline());
        assertEquals(LayoutProfile.SKILL_FLAT, profile.getSectionLayout().getSkillPresentation());
        assertEquals(
                LayoutProfile.DATE_INLINE,
                profile.getSectionLayout().getProjectHeaderDateAlignment()
        );
        String bodyFont = (profile.getBody().getFontFamily() + " " + profile.getBaseFontFamily()).toLowerCase();
        assertTrue(bodyFont.contains("helvetica") || bodyFont.contains("sans"), bodyFont);
        assertNotEquals(LayoutProfile.CONFIDENCE_LOW, profile.getConfidence());
    }

    @Test
    void leftTimesRuleFixture_detectsGenericClassicSignals() throws Exception {
        Path pdf = tempDir.resolve("left-times.pdf");
        PdfLayoutTestDocuments.writeLeftTimesRuleResume(pdf);

        LayoutProfile profile = LayoutProfileBuilder.from(extractor.extract(pdf));

        assertEquals(LayoutProfile.ALIGN_LEFT, profile.getHeaderAlignment());
        assertTrue(profile.isHasHeaderRule());
        assertTrue(profile.getSectionHeading().isUnderline());
        assertTrue(profile.getSectionHeading().isAllCaps());
        assertEquals(LayoutProfile.SKILL_GROUPED, profile.getSectionLayout().getSkillPresentation());
        assertEquals(
                LayoutProfile.DATE_RIGHT,
                profile.getSectionLayout().getProjectHeaderDateAlignment()
        );
    }

    @Test
    void emptyPdf_isLowConfidenceFallback() throws Exception {
        Path pdf = tempDir.resolve("empty.pdf");
        PdfLayoutTestDocuments.writeEmptyPage(pdf);

        LayoutProfile profile = LayoutProfileBuilder.from(extractor.extract(pdf));

        assertEquals(LayoutProfile.CONFIDENCE_LOW, profile.getConfidence());
        assertEquals(LayoutProfile.PAGE_UNKNOWN, profile.getPageSize());
        assertEquals(1, profile.getSectionLayout().getColumnCount());
    }

}
