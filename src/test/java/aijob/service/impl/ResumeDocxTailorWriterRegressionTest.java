package aijob.service.impl;

import aijob.config.StoragePathResolver;
import aijob.dto.TailorChange;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeDocxTailorWriterRegressionTest {

    private static final String NODE_ID = "p-23";
    private static final String ORIGINAL_TEXT =
            "●   Developed and integrated IP rate-limiting and black/white list management features to strengthen access control and mitigate potential attack traffic.";
    private static final String NEW_TEXT =
            "●   Developed Spring Boot security features using IP rate-limiting and black/white list management.";
    private static final Pattern BULLET_ONLY = Pattern.compile("^\\s*[●•▪◦‣∙·]\\s*$");

    private final ResumeDocumentService documentService = new ResumeDocumentServiceImpl();

    @TempDir
    Path tempDir;

    private ResumeDocxTailorService tailor;

    @BeforeEach
    void setUp() {
        tailor = new ResumeDocxTailorServiceImpl(
                documentService,
                StoragePathResolver.forRoot(tempDir.resolve("uploads"))
        );
    }

    @Test
    void writesFixedP23WithoutOpenaiAndPreservesBulletLayout() throws Exception {
        Path original = copyClasspathResume();
        byte[] originalBytesBefore = Files.readAllBytes(original);
        String originalHashBefore = sha256(originalBytesBefore);

        ParagraphLayout originalP22;
        ParagraphLayout originalP23;
        ParagraphLayout originalP24;
        try (InputStream inputStream = Files.newInputStream(original);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            originalP22 = snapshot(paragraph(document, "p-22"));
            originalP23 = snapshot(paragraph(document, NODE_ID));
            originalP24 = snapshot(paragraph(document, "p-24"));
        }

        TailorChange change = new TailorChange();
        change.setNodeId(NODE_ID);
        change.setOriginalText(ORIGINAL_TEXT);
        change.setNewText(NEW_TEXT);
        change.setApplied(true);
        change.setEvidenceType(TailorChange.EVIDENCE_VERIFIED);

        Path generated = tailor.createTailoredDocx(original, List.of(change), 0L, 0L);
        Path fixedOutput = tempDir.resolve("regression-test-output.docx");
        Files.copy(generated, fixedOutput, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(generated.startsWith(tempDir.toAbsolutePath().normalize()));
        assertFalse(generated.startsWith(Path.of("uploads").toAbsolutePath().normalize()));

        assertEquals(originalHashBefore, sha256(Files.readAllBytes(original)));
        assertTrue(MessageDigest.isEqual(originalBytesBefore, Files.readAllBytes(original)));

        try (InputStream inputStream = Files.newInputStream(fixedOutput);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            ParagraphLayout p22 = snapshot(paragraph(document, "p-22"));
            ParagraphLayout p23 = snapshot(paragraph(document, NODE_ID));
            ParagraphLayout p24 = snapshot(paragraph(document, "p-24"));

            assertEquals(normalizeBody(NEW_TEXT), normalizeBody(p23.text));
            assertTrue(p23.text.contains("Developed Spring Boot security features"));
            assertFalse(p23.text.contains("mitigate potential attack traffic"));

            assertEquals(originalP22.bulletText, p23.bulletText);
            assertEquals(p22.bulletText, p23.bulletText);
            assertEquals(p24.bulletText, p23.bulletText);
            assertEquals(p22.bulletFontFamily, p23.bulletFontFamily);
            assertEquals(p24.bulletFontFamily, p23.bulletFontFamily);
            assertEquals(p22.bodyStartRunIndex, p23.bodyStartRunIndex);
            assertEquals(p24.bodyStartRunIndex, p23.bodyStartRunIndex);
            assertEquals(p22.prefixFingerprint, p23.prefixFingerprint);
            assertEquals(p24.prefixFingerprint, p23.prefixFingerprint);

            assertEquals(originalP23.hangingIndent, p23.hangingIndent);
            assertEquals(originalP23.ctHanging, p23.ctHanging);

            assertEquals(originalP23.bodyFontFamily, p23.bodyFontFamily);
            assertEquals(originalP23.bodyFontSize, p23.bodyFontSize);

            assertEquals(originalP23.leftIndent, p23.leftIndent);
            assertEquals(originalP23.firstLineIndent, p23.firstLineIndent);
            assertEquals(originalP23.spacingBefore, p23.spacingBefore);
            assertEquals(originalP23.spacingAfter, p23.spacingAfter);
            assertEquals(originalP23.alignment, p23.alignment);
            assertEquals(originalP23.styleId, p23.styleId);
            assertEquals(originalP23.numId, p23.numId);
            assertEquals(originalP23.ctLeft, p23.ctLeft);
            assertEquals(originalP23.ctFirstLine, p23.ctFirstLine);
        }
    }

    private XWPFParagraph paragraph(XWPFDocument document, String nodeId) {
        return documentService.findParagraph(document, nodeId);
    }

    private Path copyClasspathResume() throws Exception {
        Path original = tempDir.resolve("regression-resume.docx");
        try (InputStream inputStream = getClass().getResourceAsStream("/regression/test-resume.docx")) {
            assertNotNull(inputStream, "Missing src/test/resources/regression/test-resume.docx");
            Files.copy(inputStream, original, StandardCopyOption.REPLACE_EXISTING);
        }
        return original;
    }

    private ParagraphLayout snapshot(XWPFParagraph paragraph) {
        List<XWPFRun> runs = new ArrayList<>(paragraph.getRuns());
        int bodyStart = findBodyStartRunIndex(runs);
        XWPFRun bulletRun = runs.isEmpty() ? null : runs.get(0);
        XWPFRun bodyRun = runs.get(bodyStart);

        List<String> prefix = new ArrayList<>();
        for (int i = 0; i < bodyStart; i++) {
            XWPFRun run = runs.get(i);
            String text = runText(run);
            if (isBulletOnly(text)) {
                prefix.add("BULLET");
            } else if (text.isEmpty()) {
                prefix.add("EMPTY");
            } else if (text.isBlank()) {
                prefix.add("WHITESPACE");
            } else {
                prefix.add("OTHER");
            }
        }

        return new ParagraphLayout(
                paragraph.getText(),
                bulletRun == null ? "" : runText(bulletRun),
                bulletRun == null ? null : bulletRun.getFontFamily(),
                bodyStart,
                String.join("|", prefix),
                paragraph.getIndentationLeft(),
                paragraph.getIndentationHanging(),
                paragraph.getIndentationFirstLine(),
                paragraph.getSpacingBefore(),
                paragraph.getSpacingAfter(),
                String.valueOf(paragraph.getAlignment()),
                paragraph.getStyle(),
                paragraph.getNumID() == null ? null : paragraph.getNumID().toString(),
                bodyRun.getFontFamily(),
                bodyRun.getFontSizeAsDouble(),
                ctLeft(paragraph),
                ctHanging(paragraph),
                ctFirstLine(paragraph)
        );
    }

    private int findBodyStartRunIndex(List<XWPFRun> runs) {
        for (int i = 0; i < runs.size(); i++) {
            String text = runText(runs.get(i));
            if (!text.isEmpty()
                    && !text.isBlank()
                    && !isBulletOnly(text)
                    && hasLetterOrDigit(text)) {
                return i;
            }
        }
        return 0;
    }

    private boolean isBulletOnly(String text) {
        return BULLET_ONLY.matcher(text).matches();
    }

    private boolean hasLetterOrDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String runText(XWPFRun run) {
        String text = run.text();
        return text == null ? "" : text;
    }

    private String normalizeBody(String text) {
        if (text == null) {
            return "";
        }
        String current = text.replace('\u00A0', ' ');
        current = current.replaceAll("^[\\s]*[●•▪◦‣∙·]\\s*", "");
        current = current.replaceAll("\\s+", " ").strip();
        return current;
    }

    private String ctLeft(XWPFParagraph paragraph) {
        CTInd ind = paragraphInd(paragraph);
        return ind == null ? "unset" : String.valueOf(ind.getLeft());
    }

    private String ctHanging(XWPFParagraph paragraph) {
        CTInd ind = paragraphInd(paragraph);
        return ind == null ? "unset" : String.valueOf(ind.getHanging());
    }

    private String ctFirstLine(XWPFParagraph paragraph) {
        CTInd ind = paragraphInd(paragraph);
        return ind == null ? "unset" : String.valueOf(ind.getFirstLine());
    }

    private CTInd paragraphInd(XWPFParagraph paragraph) {
        if (!paragraph.getCTP().isSetPPr()) {
            return null;
        }
        CTPPr pPr = paragraph.getCTP().getPPr();
        if (!pPr.isSetInd()) {
            return null;
        }
        return pPr.getInd();
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return HexFormat.of().formatHex(digest);
    }

    private record ParagraphLayout(
            String text,
            String bulletText,
            String bulletFontFamily,
            int bodyStartRunIndex,
            String prefixFingerprint,
            int leftIndent,
            int hangingIndent,
            int firstLineIndent,
            int spacingBefore,
            int spacingAfter,
            String alignment,
            String styleId,
            String numId,
            String bodyFontFamily,
            Double bodyFontSize,
            String ctLeft,
            String ctHanging,
            String ctFirstLine
    ) {
    }
}
