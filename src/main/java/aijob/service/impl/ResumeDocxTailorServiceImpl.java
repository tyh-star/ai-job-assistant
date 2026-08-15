package aijob.service.impl;

import aijob.dto.TailorChange;
import aijob.exception.InvalidResumeFileException;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ResumeDocxTailorServiceImpl implements ResumeDocxTailorService {

    private static final Logger log =
            LoggerFactory.getLogger(ResumeDocxTailorServiceImpl.class);

    private static final Path TAILORED_ROOT = Path.of("uploads", "tailored");
    private static final Pattern BULLET_ONLY = Pattern.compile(
            "^\\s*[●•▪◦‣∙·]\\s*$"
    );

    private final ResumeDocumentService resumeDocumentService;

    public ResumeDocxTailorServiceImpl(ResumeDocumentService resumeDocumentService) {
        this.resumeDocumentService = resumeDocumentService;
    }

    @Override
    public Path createTailoredDocx(
            Path originalDocx,
            List<TailorChange> changes,
            Long userId,
            Long jobId) {

        Path original = originalDocx.toAbsolutePath().normalize();
        Path outputPath = TAILORED_ROOT
                .resolve(String.valueOf(userId))
                .resolve(String.valueOf(jobId))
                .toAbsolutePath()
                .normalize()
                .resolve(UUID.randomUUID() + ".docx");
        Path tempPath = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");

        try {
            Files.createDirectories(outputPath.getParent());
            Files.copy(original, outputPath, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new InvalidResumeFileException("Failed to copy resume for tailoring");
        }

        try {
            try (InputStream inputStream = Files.newInputStream(outputPath);
                 XWPFDocument document = new XWPFDocument(inputStream)) {

                for (TailorChange change : changes) {
                    if (change == null || !Boolean.TRUE.equals(change.getApplied())) {
                        continue;
                    }

                    XWPFParagraph paragraph =
                            resumeDocumentService.findParagraph(
                                    document,
                                    change.getNodeId()
                            );
                    replaceParagraphTextPreservingStyle(
                            paragraph,
                            change.getNewText(),
                            change.getNodeId()
                    );
                }

                try (OutputStream outputStream = Files.newOutputStream(tempPath)) {
                    document.write(outputStream);
                }
            }

            Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
            verifyGeneratedDocx(outputPath, changes);
            return outputPath;
        } catch (RuntimeException | IOException e) {
            deleteQuietly(outputPath);
            deleteQuietly(tempPath);
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new InvalidResumeFileException("Failed to tailor DOCX resume");
        }
    }

    void replaceParagraphTextPreservingStyle(
            XWPFParagraph paragraph,
            String newText) {
        replaceParagraphTextPreservingStyle(paragraph, newText, "unknown");
    }

    void replaceParagraphTextPreservingStyle(
            XWPFParagraph paragraph,
            String newText,
            String nodeId) {

        String expected = newText == null ? "" : newText;
        String before = paragraph.getText();

        log.info("TAILOR WRITE node={} before={} expected={}",
                nodeId, before, expected);

        List<XWPFRun> runs = new ArrayList<>(paragraph.getRuns());
        if (runs.isEmpty()) {
            throw new IllegalStateException(
                    "DOCX replacement failed. nodeId=" + nodeId
                            + ", expected=" + expected
                            + ", actual="
            );
        }

        int bodyStartRunIndex = findBodyStartRunIndex(runs);
        logPrefixRuns(nodeId, runs, bodyStartRunIndex);

        XWPFRun bodyStartRun = runs.get(bodyStartRunIndex);
        RunStyleSnapshot donorStyle = snapshotRunStyle(bodyStartRun);
        String originalBodyRunText = runText(bodyStartRun);
        String bodyRunPrefix = bodyStartRunIndex == 0
                ? leadingBulletAndSeparator(originalBodyRunText)
                : leadingWhitespaceAndTabs(originalBodyRunText);
        String bodyText = stripLeadingBulletAndSeparator(expected);
        String textToWrite = bodyRunPrefix + bodyText;

        for (int i = bodyStartRunIndex; i < runs.size(); i++) {
            clearRunText(runs.get(i));
        }

        applyRunStyle(bodyStartRun, donorStyle);
        bodyStartRun.setText(textToWrite, 0);

        logFormatting("AFTER", nodeId, bodyStartRun, paragraph);

        String actual = paragraph.getText();
        log.info("TAILOR WRITE node={} after={} bodyStartRunIndex={}",
                nodeId, actual, bodyStartRunIndex);

        if (!bodyTextMatches(actual, expected)) {
            throw new IllegalStateException(
                    "DOCX replacement failed. nodeId=" + nodeId
                            + ", expected=" + expected
                            + ", actual=" + actual
            );
        }
    }

    private void verifyGeneratedDocx(Path outputPath, List<TailorChange> changes) {

        try (InputStream inputStream = Files.newInputStream(outputPath);
             XWPFDocument verificationDocument = new XWPFDocument(inputStream)) {

            for (TailorChange change : changes) {
                if (change == null || !Boolean.TRUE.equals(change.getApplied())) {
                    continue;
                }

                XWPFParagraph paragraph =
                        resumeDocumentService.findParagraph(
                                verificationDocument,
                                change.getNodeId()
                        );

                String expected = change.getNewText();
                String actual = paragraph.getText();

                log.info("SAVE VERIFY node={} path={} expected={} actual={}",
                        change.getNodeId(),
                        outputPath,
                        expected,
                        actual);

                if (!bodyTextMatches(actual, expected)) {
                    Files.deleteIfExists(outputPath);
                    throw new IllegalStateException(
                            "Saved DOCX verification failed: "
                                    + change.getNodeId()
                                    + ", expected=" + expected
                                    + ", actual=" + actual
                    );
                }
            }
        } catch (IOException e) {
            deleteQuietly(outputPath);
            throw new IllegalStateException(
                    "Saved DOCX verification failed",
                    e
            );
        }
    }

    private int findBodyStartRunIndex(List<XWPFRun> runs) {
        for (int i = 0; i < runs.size(); i++) {
            if (!isPrefixRun(runs.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private boolean isPrefixRun(XWPFRun run) {
        return isEmptyRun(run)
                || isBulletOnlyRun(run)
                || isWhitespaceOnlyRun(run)
                || isTabOnlyRun(run);
    }

    private boolean isEmptyRun(XWPFRun run) {
        return runText(run).isEmpty() && !hasTab(run);
    }

    private boolean isTabOnlyRun(XWPFRun run) {
        return hasTab(run) && !hasLetterOrDigit(runText(run));
    }

    private boolean hasTab(XWPFRun run) {
        if (runText(run).indexOf('\t') >= 0) {
            return true;
        }
        CTR ctr = run.getCTR();
        return ctr != null && (ctr.sizeOfTabArray() > 0 || ctr.sizeOfPtabArray() > 0);
    }

    private boolean isBulletOnlyRun(XWPFRun run) {
        return BULLET_ONLY.matcher(runText(run)).matches();
    }

    private boolean isWhitespaceOnlyRun(XWPFRun run) {
        String text = runText(run);
        return !text.isEmpty() && text.isBlank();
    }

    private boolean hasLetterOrDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void logPrefixRuns(String nodeId, List<XWPFRun> runs, int bodyStartRunIndex) {
        log.info("PREFIX DEBUG nodeId={} bodyStartRunIndex={}", nodeId, bodyStartRunIndex);
        for (int i = 0; i < runs.size(); i++) {
            XWPFRun run = runs.get(i);
            log.info(
                    "PREFIX DEBUG nodeId={} run{} text={} hasTab={} isWhitespaceOnly={} isBulletOnly={}",
                    nodeId,
                    i,
                    visibleRunText(runText(run)),
                    hasTab(run),
                    isWhitespaceOnlyRun(run),
                    isBulletOnlyRun(run)
            );
        }
    }

    private String visibleRunText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\t", "\\t");
    }

    private String leadingBulletAndSeparator(String text) {
        int index = 0;
        while (index < text.length() && isPrefixChar(text.charAt(index))) {
            index++;
        }
        return text.substring(0, index);
    }

    private String leadingWhitespaceAndTabs(String text) {
        int index = 0;
        while (index < text.length() && isSeparatorChar(text.charAt(index))) {
            index++;
        }
        return text.substring(0, index);
    }

    private boolean isPrefixChar(char character) {
        return isBulletChar(character) || isSeparatorChar(character);
    }

    private boolean isSeparatorChar(char character) {
        return Character.isWhitespace(character) || character == '\t' || character == '\u00A0';
    }

    private boolean isBulletChar(char character) {
        return "●•▪◦‣∙·".indexOf(character) >= 0;
    }

    private void clearRunText(XWPFRun run) {

        CTR ctr = run.getCTR();
        if (ctr == null) {
            return;
        }

        for (int i = ctr.sizeOfTArray() - 1; i >= 0; i--) {
            ctr.removeT(i);
        }
    }

    private RunStyleSnapshot snapshotRunStyle(XWPFRun run) {
        Double fontSize = run.getFontSizeAsDouble();
        if (fontSize != null && fontSize <= 0) {
            fontSize = null;
        }
        return new RunStyleSnapshot(
                run.getFontFamily(),
                fontSize,
                run.isBold(),
                run.isItalic(),
                run.getUnderline(),
                run.getColor(),
                run.getTextPosition()
        );
    }

    private void applyRunStyle(XWPFRun run, RunStyleSnapshot style) {
        if (style.fontFamily() != null) {
            run.setFontFamily(style.fontFamily());
        }
        if (style.fontSize() != null) {
            run.setFontSize(style.fontSize());
        }
        run.setBold(style.bold());
        run.setItalic(style.italic());
        if (style.underline() != null) {
            run.setUnderline(style.underline());
        }
        if (style.color() != null) {
            run.setColor(style.color());
        }
        run.setTextPosition(style.textPosition());
    }

    private record RunStyleSnapshot(
            String fontFamily,
            Double fontSize,
            boolean bold,
            boolean italic,
            UnderlinePatterns underline,
            String color,
            int textPosition
    ) {
    }

    private String runText(XWPFRun run) {
        String text = run.text();
        return text == null ? "" : text;
    }

    String stripLeadingBulletAndSeparator(String text) {
        if (text == null) {
            return "";
        }

        String current = text;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            int index = 0;
            while (index < current.length() && isSeparatorChar(current.charAt(index))) {
                index++;
            }
            if (index > 0) {
                current = current.substring(index);
                stripped = true;
            }
            if (!current.isEmpty() && isBulletChar(current.charAt(0))) {
                current = current.substring(1);
                stripped = true;
            }
        }
        return current;
    }

    String normalizeBullet(
            XWPFParagraph paragraph,
            String originalText,
            String newText) {

        boolean numbering = paragraph.getNumID() != null;
        if (numbering) {
            return stripLeadingBulletAndSeparator(newText);
        }

        String originalPrefix = leadingBulletAndSeparator(originalText == null ? "" : originalText);
        if (!originalPrefix.isEmpty()) {
            return originalPrefix + stripLeadingBulletAndSeparator(newText);
        }

        return stripLeadingBulletAndSeparator(newText);
    }

    private boolean bodyTextMatches(String actual, String expected) {
        return stripBulletAndNormalize(actual).equals(stripBulletAndNormalize(expected));
    }

    private String stripBulletAndNormalize(String text) {
        return normalizeVisibleText(stripLeadingBulletAndSeparator(text));
    }

    private String normalizeVisibleText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private void logFormatting(
            String phase,
            String nodeId,
            XWPFRun donorRun,
            XWPFParagraph paragraph) {

        log.info(
                "FORMATTING {} nodeId={} donorRun text={} fontFamily={} fontSize={} bold={} "
                        + "paragraph left indent={} hanging indent={} firstLine indent={} "
                        + "pPr left={} hanging={} firstLine={}",
                phase,
                nodeId,
                runText(donorRun),
                donorRun.getFontFamily(),
                donorRun.getFontSizeAsDouble(),
                donorRun.isBold(),
                paragraph.getIndentationLeft(),
                paragraph.getIndentationHanging(),
                paragraph.getIndentationFirstLine(),
                ctIndLeft(paragraph),
                ctIndHanging(paragraph),
                ctIndFirstLine(paragraph)
        );
    }

    private String ctIndLeft(XWPFParagraph paragraph) {
        CTInd ind = paragraphInd(paragraph);
        return ind == null ? "unset" : String.valueOf(ind.getLeft());
    }

    private String ctIndHanging(XWPFParagraph paragraph) {
        CTInd ind = paragraphInd(paragraph);
        return ind == null ? "unset" : String.valueOf(ind.getHanging());
    }

    private String ctIndFirstLine(XWPFParagraph paragraph) {
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

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // keep the original error
        }
    }
}
