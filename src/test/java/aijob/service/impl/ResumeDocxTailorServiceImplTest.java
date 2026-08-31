package aijob.service.impl;

import aijob.config.StoragePathResolver;
import aijob.dto.TailorChange;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeDocxTailorServiceImplTest {

    @TempDir
    Path tempDir;

    private ResumeDocxTailorServiceImpl tailor;

    @BeforeEach
    void setUp() {
        tailor = new ResumeDocxTailorServiceImpl(
                new ResumeDocumentServiceImpl(),
                StoragePathResolver.forRoot(tempDir.resolve("uploads"))
        );
    }

    @Test
    void replacesSingleRunParagraph() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = paragraph(document, "Implemented core CRUD operations");

            String expected = "Developed Spring Boot backend implementing core CRUD operations";
            tailor.replaceParagraphTextPreservingStyle(paragraph, expected);

            assertNormalizedEquals(expected, paragraph.getText());
        }
    }

    @Test
    void replacesMultiRunWithOneBoldPhrase() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            run(paragraph, "Developed and integrated ", false);
            run(paragraph, "IP rate-limiting", true);
            run(paragraph, " for incoming traffic", false);

            String expected = "Developed and integrated IP rate-limiting for REST traffic";
            tailor.replaceParagraphTextPreservingStyle(paragraph, expected);

            assertNormalizedEquals(expected, paragraph.getText());
        }
    }

    @Test
    void replacesMultiRunWithMultipleBoldPhrases() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            run(paragraph, "Developed and integrated ", false);
            run(paragraph, "IP rate-limiting", true);
            run(paragraph, " and ", false);
            run(paragraph, "black/white list", true);
            run(paragraph, " management", false);

            String expected = "Developed and integrated IP rate-limiting and black/white list management";
            tailor.replaceParagraphTextPreservingStyle(paragraph, expected);

            assertNormalizedEquals(expected, paragraph.getText());
        }
    }

    @Test
    void replacesParagraphWithNoEmphasisPhrases() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            run(paragraph, "Built ", false);
            run(paragraph, "REST APIs ", false);
            run(paragraph, "with Spring Boot", false);

            String expected = "Built REST APIs with Spring Boot and MyBatis";
            tailor.replaceParagraphTextPreservingStyle(paragraph, expected);

            assertNormalizedEquals(expected, paragraph.getText());
        }
    }

    @Test
    void preservesSingleTextBullet() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = paragraph(document, "● Implemented core CRUD operations");

            String expected = tailor.normalizeBullet(
                    paragraph,
                    "● Implemented core CRUD operations",
                    "● ● Developed Spring Boot CRUD operations"
            );
            tailor.replaceParagraphTextPreservingStyle(paragraph, expected);

            assertNormalizedEquals("● Developed Spring Boot CRUD operations", paragraph.getText());
            assertEquals(1, countBulletMarkers(paragraph.getText()));
        }
    }

    @Test
    void preservesWordNumberingBulletWithoutDuplicatingMarker() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            BigInteger numId = addBulletNumbering(document);
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setNumID(numId);
            run(paragraph, "Implemented core CRUD operations", false);

            String expected = tailor.normalizeBullet(
                    paragraph,
                    "Implemented core CRUD operations",
                    "● Developed Spring Boot CRUD operations"
            );
            tailor.replaceParagraphTextPreservingStyle(paragraph, expected);

            assertNormalizedEquals("Developed Spring Boot CRUD operations", paragraph.getText());
            assertEquals(0, countBulletMarkers(paragraph.getText()));
            assertEquals(numId, paragraph.getNumID());
        }
    }

    @Test
    void replacesWithLongerNewText() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = paragraph(document, "Built REST APIs");

            String expected = "Built REST APIs using Spring Boot, MyBatis, and JWT authentication";
            tailor.replaceParagraphTextPreservingStyle(paragraph, expected);

            assertNormalizedEquals(expected, paragraph.getText());
        }
    }

    @Test
    void replacesWithShorterNewText() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = paragraph(
                    document,
                    "Built REST APIs using Spring Boot, MyBatis, and JWT authentication"
            );

            String expected = "Built REST APIs";
            tailor.replaceParagraphTextPreservingStyle(paragraph, expected);

            assertNormalizedEquals(expected, paragraph.getText());
        }
    }

    @Test
    void usesLongestPlainBodyRunAsStyleDonorNotBulletOrBold() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun bullet = run(paragraph, "●", false);
            bullet.setFontFamily("Wingdings");
            bullet.setFontSize(8);
            run(paragraph, "Developed and integrated ", false);
            run(paragraph, "IP rate-limiting", true);
            run(paragraph, " features", false);

            String expected = "● Developed and integrated IP rate-limiting features for REST traffic";
            tailor.replaceParagraphTextPreservingStyle(paragraph, expected);

            assertNormalizedEquals(
                    tailor.stripLeadingBulletAndSeparator(expected),
                    tailor.stripLeadingBulletAndSeparator(paragraph.getText())
            );
            assertEquals("●", paragraph.getRuns().get(0).text());
            assertEquals("Developed and integrated IP rate-limiting features for REST traffic",
                    paragraph.getRuns().get(1).text());
            assertEquals("Calibri", paragraph.getRuns().get(1).getFontFamily());
            assertEquals(11.0, paragraph.getRuns().get(1).getFontSizeAsDouble());
            assertEquals(false, paragraph.getRuns().get(1).isBold());
            assertEquals("Wingdings", paragraph.getRuns().get(0).getFontFamily());
        }
    }

    @Test
    void restoresHangingIndentAfterRewrite() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setIndentationLeft(360);
            paragraph.setIndentationHanging(180);
            run(paragraph, "●", false);
            run(paragraph, "Developed and integrated IP rate-limiting features", false);

            String expected = "● Developed and integrated IP rate-limiting and Redis caching features";
            tailor.replaceParagraphTextPreservingStyle(paragraph, expected);

            assertNormalizedEquals(
                    tailor.stripLeadingBulletAndSeparator(expected),
                    tailor.stripLeadingBulletAndSeparator(paragraph.getText())
            );
            assertEquals(360, paragraph.getIndentationLeft());
            assertEquals(180, paragraph.getIndentationHanging());
        }
    }

    @Test
    void keepsOriginalBulletPrefixSpacingWithoutAddingIndentSpaces() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            run(paragraph, "●", false);
            run(paragraph, "   Developed and integrated features", false);

            tailor.replaceParagraphTextPreservingStyle(
                    paragraph,
                    "●   Developed Spring Boot REST features"
            );

            assertNormalizedEquals("● Developed Spring Boot REST features", paragraph.getText());
            assertEquals("●", paragraph.getRuns().get(0).text());
            assertEquals("   Developed Spring Boot REST features", paragraph.getRuns().get(1).text());
        }
    }

    @Test
    void preservesEmptySpacerRunBetweenBulletAndBody() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            run(paragraph, "●", false);
            paragraph.createRun();
            run(paragraph, "Developed and integrated features", false);

            tailor.replaceParagraphTextPreservingStyle(
                    paragraph,
                    "●   Integrated Redis caching features"
            );

            assertEquals("●", paragraph.getRuns().get(0).text());
            assertEquals("", paragraph.getRuns().get(1).text());
            assertEquals("Integrated Redis caching features", paragraph.getRuns().get(2).text());
        }
    }

    @Test
    void savedDocxStillContainsAppliedText() throws Exception {
        Path original = tempDir.resolve("original.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream outputStream = Files.newOutputStream(original)) {
            paragraph(document, "Implemented core CRUD operations");
            document.write(outputStream);
        }

        TailorChange change = new TailorChange();
        change.setNodeId("p-0");
        change.setOriginalText("Implemented core CRUD operations");
        change.setNewText("Developed Spring Boot backend implementing core CRUD operations");
        change.setApplied(true);

        Path generated = tailor.createTailoredDocx(original, List.of(change), 1L, 1L);
        assertTrue(generated.startsWith(tempDir.toAbsolutePath().normalize()));

        try (InputStream inputStream = Files.newInputStream(generated);
             XWPFDocument generatedDocument = new XWPFDocument(inputStream)) {
            XWPFParagraph paragraph = generatedDocument.getParagraphs().get(0);
            assertNormalizedEquals(change.getNewText(), paragraph.getText());
        }
    }

    private XWPFParagraph paragraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        run(paragraph, text, false);
        return paragraph;
    }

    private XWPFRun run(XWPFParagraph paragraph, String text, boolean bold) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Calibri");
        run.setFontSize(11);
        run.setBold(bold);
        run.setText(text);
        return run;
    }

    private void assertNormalizedEquals(String expected, String actual) {
        assertEquals(normalize(expected), normalize(actual));
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private int countBulletMarkers(String text) {
        int count = 0;
        for (char character : text.toCharArray()) {
            if ("●•▪◦‣∙·".indexOf(character) >= 0) {
                count++;
            }
        }
        return count;
    }

    private BigInteger addBulletNumbering(XWPFDocument document) {
        CTAbstractNum ctAbstractNum = CTAbstractNum.Factory.newInstance();
        ctAbstractNum.setAbstractNumId(BigInteger.ZERO);
        CTLvl level = ctAbstractNum.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewNumFmt().setVal(STNumberFormat.BULLET);
        level.addNewLvlText().setVal("•");
        level.addNewStart().setVal(BigInteger.ONE);

        XWPFAbstractNum abstractNum = new XWPFAbstractNum(ctAbstractNum);
        XWPFNumbering numbering = document.createNumbering();
        BigInteger abstractNumId = numbering.addAbstractNum(abstractNum);
        return numbering.addNum(abstractNumId);
    }
}
