package aijob.service.impl;

import aijob.dto.LayoutProfile;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructure.ResumeBullet;
import aijob.dto.ResumeStructure.ResumeEducation;
import aijob.dto.ResumeStructure.ResumeProject;
import aijob.dto.ResumeStructure.ResumeSkill;
import aijob.service.ResumeDocumentService;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeTemplateDocxServiceImplTest {

    private final ResumeTemplateDocxServiceImpl writer = new ResumeTemplateDocxServiceImpl();
    private final ResumeDocumentService documentService = new ResumeDocumentServiceImpl();

    @TempDir
    Path tempDir;

    @Test
    void write_yaohangStructure_keepsOriginalFactsOnLetterPage() throws Exception {
        Path output = tempDir.resolve("canonical.docx");
        writer.write(yaohangStructure(), output);

        assertTrue(Files.exists(output));
        try (InputStream inputStream = Files.newInputStream(output);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            CTPageSz pageSz = document.getDocument().getBody().getSectPr().getPgSz();
            assertEquals(12240, ((Number) pageSz.getW()).intValue());
            assertEquals(15840, ((Number) pageSz.getH()).intValue());
            String text = document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText() == null ? "" : paragraph.getText())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
            assertTrue(text.contains("Yaohang Tang"), text);
            assertTrue(text.contains("SUMMARY"), text);
            assertTrue(text.contains("Strong understanding of SDLC methodologies"), text);
            assertTrue(text.contains("Experience with Git flow and version control"), text);
            assertFalse(text.contains("Strong understanding of SDLC methodologies, including Agile and Waterfall Exceptional"), text);
            assertTrue(text.contains("TECHNICAL SKILLS"), text);
            assertTrue(text.contains("Java"), text);
            assertTrue(text.contains("PROJECTS"), text);
            assertTrue(text.contains("Prime Deals"), text);
            assertTrue(text.contains("Personal Project"), text);
            assertTrue(text.contains("Sept 2024 - Present"), text);
            assertTrue(text.contains("http://35.183.41.31:8080/login.html"), text);
            assertTrue(text.contains("SmartPC Builder"), text);
            assertTrue(text.contains("York University"), text);
            assertTrue(text.contains("EDUCATION"), text);
            assertTrue(text.contains("Bachelor of Computer Science"), text);
            assertTrue(text.contains("Relevant Courses: Algorithm, Data Structure, Advanced OOP"), text);
            assertFalse(text.contains("Docker"), text);
            assertFalse(text.contains("Kubernetes"), text);
        }

        List<ResumeDocumentNode> nodes = documentService.parseDocx(output);
        assertFalse(nodes.isEmpty());
        String joined = nodes.stream().map(ResumeDocumentNode::getText).reduce((a, b) -> a + "\n" + b).orElse("");
        assertTrue(joined.contains("Yaohang Tang"), joined);
        assertTrue(joined.contains("Prime Deals"), joined);
        assertTrue(nodes.stream().anyMatch(node -> node.getNodeId() != null && node.getNodeId().startsWith("p-")));
    }

    @Test
    void write_highYaohangLikeProfile_usesLeftRuleUnderlineGroupedRightDates() throws Exception {
        ResumeStructure structure = yaohangStructure();
        structure.setLayoutProfile(yaohangLikeProfile());
        structure.getSkills().clear();
        structure.getSkills().add(skill("Java", "Programming Languages: C/C++, Java, Python"));
        structure.getSkills().add(skill("Python", "Programming Languages: C/C++, Java, Python"));
        structure.getSkills().add(skill("HTML", "Web Technologies: HTML, CSS"));

        Path output = tempDir.resolve("yaohang-like.docx");
        writer.write(structure, output);

        try (InputStream inputStream = Files.newInputStream(output);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            assertNoOverlap(document);
            XWPFParagraph name = document.getParagraphs().get(0);
            assertEquals(ParagraphAlignment.LEFT, name.getAlignment());
            assertEquals("Times New Roman", name.getRuns().get(0).getFontFamily());
            assertTrue(hasHeaderRule(document));
            assertTrue(hasUnderlinedHeading(document, "PROJECTS"));
            String text = paragraphText(document);
            assertTrue(text.contains("Programming Languages: Java, Python"), text);
            assertTrue(text.contains("Web Technologies: HTML"), text);
            assertFalse(text.contains("Docker"), text);
            assertTrue(hasRightAlignedDates(document, "Prime Deals", "Sept 2024 - Present"));
            assertFalse(text.contains("Prime Deals | Personal Project | Sept 2024 - Present"), text);
        }

        List<ResumeDocumentNode> nodes = documentService.parseDocx(output);
        assertTrue(nodes.stream().anyMatch(node -> node.getText().contains("Prime Deals")));
        assertTrue(nodes.stream().anyMatch(node -> node.getText().contains("Programming Languages")));
    }

    @Test
    void write_centeredSansProfile_doesNotEmitYaohangStyle() throws Exception {
        ResumeStructure structure = yaohangStructure();
        structure.getContact().setFullName("Alex Chen");
        structure.setLayoutProfile(centeredSansProfile());
        structure.getSkills().clear();
        ResumeSkill java = new ResumeSkill();
        java.setName("Java");
        structure.getSkills().add(java);
        ResumeSkill python = new ResumeSkill();
        python.setName("Python");
        structure.getSkills().add(python);

        Path output = tempDir.resolve("centered.docx");
        writer.write(structure, output);

        try (InputStream inputStream = Files.newInputStream(output);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            assertNoOverlap(document);
            XWPFParagraph name = document.getParagraphs().get(0);
            assertEquals(ParagraphAlignment.CENTER, name.getAlignment());
            assertEquals("Arial", name.getRuns().get(0).getFontFamily());
            assertFalse(hasHeaderRule(document));
            assertFalse(hasUnderlinedHeading(document, "PROJECTS"));
            String text = paragraphText(document);
            assertTrue(text.contains("Java, Python"), text);
            assertFalse(text.contains("Programming Languages:"), text);
            assertTrue(text.contains("Prime Deals | Personal Project | Sept 2024 - Present"), text);
            assertFalse(hasRightAlignedDates(document, "Prime Deals", "Sept 2024 - Present"));
        }
    }

    @Test
    void write_lowConfidenceProfile_fallsBackToAtsWithoutCrashing() throws Exception {
        ResumeStructure structure = yaohangStructure();
        structure.setLayoutProfile(LayoutProfile.lowConfidence());

        Path output = tempDir.resolve("fallback.docx");
        writer.write(structure, output);

        try (InputStream inputStream = Files.newInputStream(output);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            assertNoOverlap(document);
            XWPFParagraph name = document.getParagraphs().get(0);
            assertEquals(ParagraphAlignment.CENTER, name.getAlignment());
            assertEquals("Calibri", name.getRuns().get(0).getFontFamily());
            assertFalse(hasHeaderRule(document));
            String text = paragraphText(document);
            assertTrue(text.contains("Yaohang Tang"), text);
            assertTrue(text.contains("Prime Deals | Personal Project | Sept 2024 - Present"), text);
            assertTrue(text.contains("Java"), text);
        }
        List<ResumeDocumentNode> nodes = documentService.parseDocx(output);
        assertFalse(nodes.isEmpty());
        assertTrue(nodes.stream().anyMatch(node -> "p-0".equals(node.getNodeId())));
    }

    private static ResumeStructure yaohangStructure() {
        ResumeStructure structure = ResumeStructure.empty();
        structure.getContact().setFullName("Yaohang Tang");
        structure.getContact().setEmail("yaohangtang@gmail.com");
        structure.getContact().setPhone("(647) 853-8928");
        structure.getContact().getLinks().add("https://github.com/tyh-star");
        structure.getSummaryBullets().add(bullet("Strong understanding of SDLC methodologies, including Agile and Waterfall"));
        structure.getSummaryBullets().add(bullet("Exceptional analytical and problem-solving skills with continuous thrive in optimizing program functionalities"));
        structure.getSummaryBullets().add(bullet("Quick learner with a passion for adapting new business processes, latest technologies and concepts"));
        structure.getSummaryBullets().add(bullet("Proficient in multiple programming languages, including Java, Python, Vue, Javascript"));
        structure.getSummaryBullets().add(bullet("Strong understanding of computer science fundamentals including data structures and algorithms"));
        structure.getSummaryBullets().add(bullet("Excellent communication and teamwork skills, with a proven ability to work independently and with a team"));
        structure.getSummaryBullets().add(bullet("Experience with Git flow and version control"));
        ResumeSkill java = new ResumeSkill();
        java.setName("Java");
        structure.getSkills().add(java);
        ResumeProject prime = new ResumeProject();
        prime.normalize();
        prime.setName("Prime Deals");
        prime.setOrganizationOrType("Personal Project");
        prime.setDates("Sept 2024 - Present");
        prime.getBullets().add("Lead and collaborate with a friend to develop a shopping website");
        prime.getLinks().add("http://35.183.41.31:8080/login.html");
        structure.getProjects().add(prime);
        ResumeProject smart = new ResumeProject();
        smart.normalize();
        smart.setName("SmartPC Builder");
        smart.setOrganizationOrType("York University");
        smart.setDates("Jan 2025 - Mar 2025");
        smart.getBullets().add("Developed a Java Maven project that helps users select optimal components within a given budget");
        structure.getProjects().add(smart);
        ResumeEducation education = new ResumeEducation();
        education.setSchool("York University");
        education.setDegree("Bachelor of Computer Science");
        education.setDates("Sept 2023 – Present");
        education.setDetails("Relevant Courses: Algorithm, Data Structure, Advanced OOP");
        structure.getEducation().add(education);
        return structure;
    }

    private static ResumeBullet bullet(String text) {
        ResumeBullet bullet = new ResumeBullet();
        bullet.setText(text);
        return bullet;
    }

    private static ResumeSkill skill(String name, String sourceText) {
        ResumeSkill skill = new ResumeSkill();
        skill.setName(name);
        skill.setSourceText(sourceText);
        return skill;
    }

    private static LayoutProfile yaohangLikeProfile() {
        LayoutProfile profile = new LayoutProfile();
        profile.normalize();
        profile.setVersion(1);
        profile.setPageSize(LayoutProfile.PAGE_LETTER);
        profile.setMarginTopPt(54f);
        profile.setMarginBottomPt(54f);
        profile.setMarginLeftPt(54f);
        profile.setMarginRightPt(54f);
        profile.setBaseFontFamily("TimesNewRomanPSMT");
        profile.setBaseFontSizePt(11f);
        profile.setHeaderAlignment(LayoutProfile.ALIGN_LEFT);
        profile.setContactAlignment(LayoutProfile.ALIGN_LEFT);
        profile.setHasHeaderRule(true);
        profile.getSectionHeading().setFontFamily("TimesNewRomanPS-BoldMT");
        profile.getSectionHeading().setFontSizePt(12f);
        profile.getSectionHeading().setBold(true);
        profile.getSectionHeading().setUnderline(true);
        profile.getSectionHeading().setAllCaps(true);
        profile.getBody().setFontFamily("TimesNewRomanPSMT");
        profile.getBody().setFontSizePt(11f);
        profile.getBody().setLineSpacing(1.15f);
        profile.getBody().setSpaceAfterPt(4f);
        profile.getBullet().setEnabled(true);
        profile.getBullet().setSymbol("•");
        profile.getBullet().setLeftIndentPt(18f);
        profile.getBullet().setHangingIndentPt(12f);
        profile.getSectionLayout().setProjectHeaderDateAlignment(LayoutProfile.DATE_RIGHT);
        profile.getSectionLayout().setEducationDateAlignment(LayoutProfile.DATE_RIGHT);
        profile.getSectionLayout().setSkillPresentation(LayoutProfile.SKILL_GROUPED);
        profile.getSectionLayout().setColumnCount(1);
        profile.setConfidence(LayoutProfile.CONFIDENCE_HIGH);
        profile.normalize();
        return profile;
    }

    private static LayoutProfile centeredSansProfile() {
        LayoutProfile profile = new LayoutProfile();
        profile.normalize();
        profile.setVersion(1);
        profile.setPageSize(LayoutProfile.PAGE_LETTER);
        profile.setMarginTopPt(72f);
        profile.setMarginBottomPt(72f);
        profile.setMarginLeftPt(72f);
        profile.setMarginRightPt(72f);
        profile.setBaseFontFamily("Helvetica");
        profile.setBaseFontSizePt(11f);
        profile.setHeaderAlignment(LayoutProfile.ALIGN_CENTER);
        profile.setContactAlignment(LayoutProfile.ALIGN_CENTER);
        profile.setHasHeaderRule(false);
        profile.getSectionHeading().setFontFamily("Helvetica-Bold");
        profile.getSectionHeading().setFontSizePt(12f);
        profile.getSectionHeading().setBold(true);
        profile.getSectionHeading().setUnderline(false);
        profile.getSectionHeading().setAllCaps(true);
        profile.getBody().setFontFamily("Helvetica");
        profile.getBody().setFontSizePt(11f);
        profile.getBody().setLineSpacing(1.15f);
        profile.getBody().setSpaceAfterPt(6f);
        profile.getSectionLayout().setProjectHeaderDateAlignment(LayoutProfile.DATE_INLINE);
        profile.getSectionLayout().setEducationDateAlignment(LayoutProfile.DATE_INLINE);
        profile.getSectionLayout().setSkillPresentation(LayoutProfile.SKILL_FLAT);
        profile.getSectionLayout().setColumnCount(1);
        profile.setConfidence(LayoutProfile.CONFIDENCE_HIGH);
        profile.normalize();
        return profile;
    }

    private static String paragraphText(XWPFDocument document) {
        return document.getParagraphs().stream()
                .map(paragraph -> paragraph.getText() == null ? "" : paragraph.getText())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static boolean hasHeaderRule(XWPFDocument document) {
        return document.getParagraphs().stream()
                .anyMatch(paragraph -> paragraph.getBorderBottom() != null
                        && paragraph.getBorderBottom() != Borders.NONE
                        && paragraph.getBorderBottom() != Borders.NIL);
    }

    private static boolean hasUnderlinedHeading(XWPFDocument document, String heading) {
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            if (paragraph.getText() == null || !paragraph.getText().contains(heading)) {
                continue;
            }
            for (XWPFRun run : paragraph.getRuns()) {
                if (run.getUnderline() != null && run.getUnderline() != UnderlinePatterns.NONE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRightAlignedDates(XWPFDocument document, String left, String dates) {
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = paragraph.getText() == null ? "" : paragraph.getText();
            if (!text.contains(left) || !text.contains(dates)) {
                continue;
            }
            if (text.contains("\t")) {
                return true;
            }
            CTPPr ppr = paragraph.getCTP().getPPr();
            if (ppr != null && ppr.isSetTabs()) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoOverlap(XWPFDocument document) {
        assertTrue(document.getTables().isEmpty());
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            CTPPr ppr = paragraph.getCTP().getPPr();
            if (ppr != null) {
                assertFalse(ppr.isSetFramePr());
            }
        }
    }
}
