package aijob.service.impl;

import aijob.dto.builder.NormalizedResume;
import aijob.dto.builder.NormalizedResume.Header;
import aijob.dto.builder.NormalizedResume.NormalizedBlock;
import aijob.dto.builder.NormalizedResume.NormalizedSection;
import aijob.service.ResumeBuilderDocxService;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabStop;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabs;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabJc;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link NormalizedResume} as a single-column, Canadian-ATS-style DOCX: one
 * fixed style (Letter page, Calibri, conservative sizes/margins), no tables, no text
 * boxes, no columns -- the layout choices ATS parsers handle reliably. Never receives
 * raw draft content, only the already blank-omitted, already-ordered
 * {@link NormalizedResume} -- see that class's javadoc for why preview and this writer
 * can never disagree. A sibling of {@link ResumeTemplateDocxServiceImpl}, not a caller
 * of it -- that class renders an UPLOADED resume's own inferred layout profile, which
 * has no meaning for a from-scratch generated one.
 */
@Service
public class ResumeBuilderDocxServiceImpl implements ResumeBuilderDocxService {

    private static final String FONT = "Calibri";
    private static final int NAME_SIZE = 18;
    private static final int TARGET_TITLE_SIZE = 12;
    private static final int HEADING_SIZE = 12;
    private static final int BODY_SIZE = 11;
    private static final BigInteger PAGE_WIDTH = BigInteger.valueOf(12240); // US Letter
    private static final BigInteger PAGE_HEIGHT = BigInteger.valueOf(15840);
    private static final BigInteger MARGIN = BigInteger.valueOf(1080); // 0.75in
    private static final int RIGHT_TAB_TWIPS = PAGE_WIDTH.intValue() - MARGIN.intValue() * 2;

    @Override
    public void write(NormalizedResume resume, Path outputPath) {
        if (outputPath == null) {
            throw new IllegalArgumentException("Output path is required");
        }
        if (resume == null) {
            throw new IllegalArgumentException("Normalized resume is required");
        }
        try {
            Files.createDirectories(outputPath.getParent());
            try (XWPFDocument document = new XWPFDocument();
                 OutputStream outputStream = Files.newOutputStream(outputPath)) {
                applyPage(document);
                BigInteger bulletNumId = addBulletNumbering(document);
                writeHeader(document, resume.getHeader());
                writeSummary(document, resume.getSummary());
                for (NormalizedSection section : resume.getSections()) {
                    writeSection(document, section, bulletNumId);
                }
                document.write(outputStream);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write generated resume", e);
        }
    }

    private void applyPage(XWPFDocument document) {
        CTSectPr sectPr = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();
        CTPageSz pageSz = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        pageSz.setOrient(STPageOrientation.PORTRAIT);
        pageSz.setW(PAGE_WIDTH);
        pageSz.setH(PAGE_HEIGHT);
        CTPageMar margin = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        margin.setTop(MARGIN);
        margin.setBottom(MARGIN);
        margin.setLeft(MARGIN);
        margin.setRight(MARGIN);
        margin.setHeader(BigInteger.valueOf(720));
        margin.setFooter(BigInteger.valueOf(720));
    }

    private void writeHeader(XWPFDocument document, Header header) {
        if (header == null) {
            return;
        }
        if (!isBlank(header.getFullName())) {
            XWPFParagraph name = document.createParagraph();
            name.setSpacingAfter(60);
            run(name, header.getFullName(), NAME_SIZE, true, false);
        }
        if (!isBlank(header.getTargetTitle())) {
            XWPFParagraph title = document.createParagraph();
            title.setSpacingAfter(80);
            run(title, header.getTargetTitle(), TARGET_TITLE_SIZE, false, false);
        }
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, header.getLocation());
        addIfPresent(parts, header.getPhone());
        addIfPresent(parts, header.getEmail());
        if (header.getLinks() != null) {
            header.getLinks().forEach(link -> addIfPresent(parts, link));
        }
        if (!parts.isEmpty()) {
            XWPFParagraph line = document.createParagraph();
            line.setSpacingAfter(200);
            run(line, String.join("  |  ", parts), BODY_SIZE, false, false);
        }
    }

    private void writeSummary(XWPFDocument document, String summary) {
        if (isBlank(summary)) {
            return;
        }
        heading(document, "SUMMARY");
        body(document, summary);
    }

    private void writeSection(XWPFDocument document, NormalizedSection section, BigInteger bulletNumId) {
        if (section == null || section.getBlocks().isEmpty()) {
            return;
        }
        heading(document, section.getTitle());
        for (NormalizedBlock block : section.getBlocks()) {
            if (!block.getHeading().isBlank() || !block.getMeta().isBlank()) {
                blockHeader(document, block.getHeading(), block.getMeta());
            }
            for (String line : block.getLines()) {
                if (block.isBulletedLines()) {
                    bullet(document, line, bulletNumId);
                } else {
                    body(document, line);
                }
            }
        }
    }

    private void blockHeader(XWPFDocument document, String headingText, String metaText) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(40);
        if (!metaText.isBlank()) {
            applyRightTab(paragraph, RIGHT_TAB_TWIPS);
        }
        if (!headingText.isBlank()) {
            run(paragraph, headingText, BODY_SIZE, true, false);
        }
        if (!metaText.isBlank()) {
            XWPFRun tabRun = paragraph.createRun();
            tabRun.addTab();
            run(paragraph, metaText, BODY_SIZE, false, true);
        }
    }

    private void heading(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(200);
        paragraph.setSpacingAfter(80);
        paragraph.setBorderBottom(org.apache.poi.xwpf.usermodel.Borders.SINGLE);
        run(paragraph, text.toUpperCase(Locale.ROOT), HEADING_SIZE, true, false);
    }

    private void body(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(80);
        run(paragraph, text, BODY_SIZE, false, false);
    }

    private void bullet(XWPFDocument document, String text, BigInteger bulletNumId) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setNumID(bulletNumId);
        paragraph.setSpacingAfter(40);
        run(paragraph, text, BODY_SIZE, false, false);
    }

    private void run(XWPFParagraph paragraph, String text, int size, boolean bold, boolean italic) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(size);
        run.setBold(bold);
        run.setItalic(italic);
        run.setText(text);
    }

    private void applyRightTab(XWPFParagraph paragraph, int rightTwips) {
        CTPPr ppr = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        CTTabs tabs = ppr.isSetTabs() ? ppr.getTabs() : ppr.addNewTabs();
        CTTabStop tab = tabs.addNewTab();
        tab.setVal(STTabJc.RIGHT);
        tab.setPos(BigInteger.valueOf(Math.max(rightTwips, 1440)));
    }

    private BigInteger addBulletNumbering(XWPFDocument document) {
        CTAbstractNum ctAbstractNum = CTAbstractNum.Factory.newInstance();
        ctAbstractNum.setAbstractNumId(BigInteger.ZERO);
        CTLvl level = ctAbstractNum.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewNumFmt().setVal(STNumberFormat.BULLET);
        level.addNewLvlText().setVal("•");
        level.addNewStart().setVal(BigInteger.ONE);
        CTInd ind = level.addNewPPr().addNewInd();
        ind.setLeft(BigInteger.valueOf(360));
        ind.setHanging(BigInteger.valueOf(180));
        XWPFAbstractNum abstractNum = new XWPFAbstractNum(ctAbstractNum);
        XWPFNumbering numbering = document.createNumbering();
        BigInteger abstractNumId = numbering.addAbstractNum(abstractNum);
        return numbering.addNum(abstractNumId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void addIfPresent(List<String> target, String value) {
        if (!isBlank(value)) {
            target.add(value);
        }
    }
}
