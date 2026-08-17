package aijob.service.impl;

import aijob.dto.LayoutProfile;
import aijob.dto.LayoutProfile.Body;
import aijob.dto.LayoutProfile.Bullet;
import aijob.dto.LayoutProfile.SectionHeading;
import aijob.dto.LayoutProfile.SectionLayout;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructure.ResumeBullet;
import aijob.dto.ResumeStructure.ResumeContact;
import aijob.dto.ResumeStructure.ResumeEducation;
import aijob.dto.ResumeStructure.ResumeExperience;
import aijob.dto.ResumeStructure.ResumeProject;
import aijob.dto.ResumeStructure.ResumeSkill;
import aijob.service.ResumeTemplateDocxService;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ResumeTemplateDocxServiceImpl implements ResumeTemplateDocxService {

    private static final String FALLBACK_FONT = "Calibri";
    private static final BigInteger LETTER_WIDTH = BigInteger.valueOf(12240);
    private static final BigInteger LETTER_HEIGHT = BigInteger.valueOf(15840);
    private static final BigInteger A4_WIDTH = BigInteger.valueOf(11906);
    private static final BigInteger A4_HEIGHT = BigInteger.valueOf(16838);
    private static final BigInteger FALLBACK_MARGIN = BigInteger.valueOf(1080);

    @Override
    public void write(ResumeStructure structure, Path outputPath) {
        if (outputPath == null) {
            throw new IllegalArgumentException("Canonical resume path is required");
        }
        try {
            Files.createDirectories(outputPath.getParent());
            try (XWPFDocument document = new XWPFDocument();
                 OutputStream outputStream = Files.newOutputStream(outputPath)) {
                RenderStyle style = RenderStyle.from(structure == null ? null : structure.getLayoutProfile());
                applyPage(document, style);
                BigInteger bullets = addBulletNumbering(document, style);
                writeContact(document, structure.getContact(), style);
                writeSummary(document, structure.getSummaryBullets(), bullets, style);
                writeSkills(document, structure.getSkills(), style);
                writeExperience(document, structure.getExperience(), bullets, style);
                writeProjects(document, structure.getProjects(), bullets, style);
                writeEducation(document, structure.getEducation(), style);
                document.write(outputStream);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write canonical resume", e);
        }
    }

    private void applyPage(XWPFDocument document, RenderStyle style) {
        CTSectPr sectPr = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();
        CTPageSz pageSz = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        pageSz.setOrient(STPageOrientation.PORTRAIT);
        pageSz.setW(style.pageWidth);
        pageSz.setH(style.pageHeight);
        CTPageMar margin = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        margin.setTop(style.marginTop);
        margin.setBottom(style.marginBottom);
        margin.setLeft(style.marginLeft);
        margin.setRight(style.marginRight);
        margin.setHeader(BigInteger.valueOf(720));
        margin.setFooter(BigInteger.valueOf(720));
    }

    private void writeContact(XWPFDocument document, ResumeContact contact, RenderStyle style) {
        if (contact == null) {
            return;
        }
        if (!isBlank(contact.getFullName())) {
            XWPFParagraph name = document.createParagraph();
            name.setAlignment(style.headerAlignment);
            name.setSpacingAfter(80);
            run(name, contact.getFullName(), style.font, style.nameSize, true, false);
        }
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, contact.getPhone());
        addIfPresent(parts, contact.getEmail());
        addIfPresent(parts, contact.getLocation());
        if (contact.getLinks() != null) {
            for (String link : contact.getLinks()) {
                addIfPresent(parts, link);
            }
        }
        if (!parts.isEmpty()) {
            XWPFParagraph line = document.createParagraph();
            line.setAlignment(style.contactAlignment);
            line.setSpacingAfter(style.headerRule ? 80 : 200);
            run(line, String.join(" | ", parts), style.font, style.bodySize, false, false);
            if (style.headerRule) {
                line.setBorderBottom(Borders.SINGLE);
            }
        } else if (style.headerRule) {
            XWPFParagraph rule = document.createParagraph();
            rule.setSpacingAfter(200);
            rule.setBorderBottom(Borders.SINGLE);
            run(rule, " ", style.font, style.bodySize, false, false);
        }
    }

    private void writeSummary(
            XWPFDocument document,
            List<ResumeBullet> bullets,
            BigInteger numbering,
            RenderStyle style) {
        List<String> lines = new ArrayList<>();
        if (bullets != null) {
            for (ResumeBullet bullet : bullets) {
                addIfPresent(lines, bullet == null ? null : bullet.getText());
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        heading(document, "SUMMARY", style);
        for (String line : lines) {
            bullet(document, line, numbering, style);
        }
    }

    private void writeSkills(XWPFDocument document, List<ResumeSkill> skills, RenderStyle style) {
        List<ResumeSkill> present = new ArrayList<>();
        if (skills != null) {
            for (ResumeSkill skill : skills) {
                if (skill != null && !isBlank(skill.getName())) {
                    present.add(skill);
                }
            }
        }
        if (present.isEmpty()) {
            return;
        }
        heading(document, "TECHNICAL SKILLS", style);
        if (LayoutProfile.SKILL_GROUPED.equals(style.skillPresentation)) {
            writeGroupedSkills(document, present, style);
            return;
        }
        List<String> names = new ArrayList<>();
        for (ResumeSkill skill : present) {
            names.add(skill.getName().trim());
        }
        body(document, String.join(", ", names), style);
    }

    private void writeGroupedSkills(XWPFDocument document, List<ResumeSkill> skills, RenderStyle style) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        List<String> ungrouped = new ArrayList<>();
        for (ResumeSkill skill : skills) {
            String label = groupLabel(skill.getSourceText(), skill.getName());
            if (label == null) {
                ungrouped.add(skill.getName().trim());
            } else {
                groups.computeIfAbsent(label, key -> new ArrayList<>()).add(skill.getName().trim());
            }
        }
        if (groups.isEmpty()) {
            body(document, String.join(", ", ungrouped), style);
            return;
        }
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            body(document, entry.getKey() + ": " + String.join(", ", entry.getValue()), style);
        }
        if (!ungrouped.isEmpty()) {
            body(document, String.join(", ", ungrouped), style);
        }
    }

    private String groupLabel(String sourceText, String skillName) {
        if (isBlank(sourceText) || isBlank(skillName) || !sourceText.contains(":")) {
            return null;
        }
        String needle = skillName.trim();
        for (String line : sourceText.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0 || colon > 40) {
                continue;
            }
            String label = line.substring(0, colon).trim();
            String items = line.substring(colon + 1);
            if (label.isBlank() || items.isBlank()) {
                continue;
            }
            if (containsIgnoreCase(items, needle) || containsIgnoreCase(line, needle)) {
                return label;
            }
        }
        return null;
    }

    private void writeExperience(
            XWPFDocument document,
            List<ResumeExperience> jobs,
            BigInteger numbering,
            RenderStyle style) {
        List<ResumeExperience> present = new ArrayList<>();
        if (jobs != null) {
            for (ResumeExperience job : jobs) {
                if (job != null && hasExperience(job)) {
                    present.add(job);
                }
            }
        }
        if (present.isEmpty()) {
            return;
        }
        heading(document, "EXPERIENCE", style);
        for (ResumeExperience job : present) {
            String titleCompany = join(" | ", job.getTitle(), job.getCompany());
            String dates = join(" - ", job.getStartDate(), job.getEndDate());
            writeDatedHeader(document, titleCompany, dates, style.projectDateAlign, style);
            if (!isBlank(job.getLocation())) {
                body(document, job.getLocation().trim(), style);
            }
            if (job.getBullets() != null) {
                for (String bullet : job.getBullets()) {
                    if (!isBlank(bullet)) {
                        bullet(document, bullet.trim(), numbering, style);
                    }
                }
            }
        }
    }

    private void writeProjects(
            XWPFDocument document,
            List<ResumeProject> projects,
            BigInteger numbering,
            RenderStyle style) {
        List<ResumeProject> present = new ArrayList<>();
        if (projects != null) {
            for (ResumeProject project : projects) {
                if (project != null && hasProject(project)) {
                    present.add(project);
                }
            }
        }
        if (present.isEmpty()) {
            return;
        }
        heading(document, "PROJECTS", style);
        for (ResumeProject project : present) {
            String header = join(" | ", project.getName(), project.getOrganizationOrType());
            writeDatedHeader(document, header, project.getDates(), style.projectDateAlign, style);
            if (project.getBullets() != null) {
                for (String bullet : project.getBullets()) {
                    if (!isBlank(bullet)) {
                        bullet(document, bullet.trim(), numbering, style);
                    }
                }
            }
            if (project.getLinks() != null) {
                for (String link : project.getLinks()) {
                    if (!isBlank(link)) {
                        bullet(document, link.trim(), numbering, style);
                    }
                }
            }
        }
    }

    private void writeEducation(XWPFDocument document, List<ResumeEducation> schools, RenderStyle style) {
        List<ResumeEducation> present = new ArrayList<>();
        if (schools != null) {
            for (ResumeEducation school : schools) {
                if (school != null && hasEducation(school)) {
                    present.add(school);
                }
            }
        }
        if (present.isEmpty()) {
            return;
        }
        heading(document, "EDUCATION", style);
        for (ResumeEducation school : present) {
            String header = join(", ", school.getDegree(), school.getSchool());
            writeDatedHeader(document, header, school.getDates(), style.educationDateAlign, style);
            if (!isBlank(school.getDetails())) {
                body(document, school.getDetails().trim(), style);
            }
        }
    }

    private void writeDatedHeader(
            XWPFDocument document,
            String left,
            String dates,
            String alignment,
            RenderStyle style) {
        String trimmedLeft = left == null ? "" : left.trim();
        String trimmedDates = dates == null ? "" : dates.trim();
        if (trimmedLeft.isBlank() && trimmedDates.isBlank()) {
            return;
        }
        if (LayoutProfile.DATE_RIGHT.equals(alignment) && !trimmedLeft.isBlank() && !trimmedDates.isBlank()) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setSpacingAfter(style.spaceAfterTwips / 2);
            applyRightTab(paragraph, style.usableWidthTwips);
            run(paragraph, trimmedLeft, style.font, style.bodySize, true, false);
            run(paragraph, "\t" + trimmedDates, style.font, style.bodySize, true, false);
            return;
        }
        if (LayoutProfile.DATE_LEFT.equals(alignment)) {
            if (!trimmedLeft.isBlank()) {
                meta(document, trimmedLeft, style);
            }
            if (!trimmedDates.isBlank()) {
                body(document, trimmedDates, style);
            }
            return;
        }
        String inline = join(" | ", trimmedLeft, trimmedDates);
        if (!inline.isBlank()) {
            meta(document, inline, style);
        }
    }

    private void heading(XWPFDocument document, String text, RenderStyle style) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(200);
        paragraph.setSpacingAfter(80);
        String value = style.headingAllCaps ? text.toUpperCase(Locale.ROOT) : text;
        run(paragraph, value, style.headingFont, style.headingSize, style.headingBold, style.headingUnderline);
    }

    private void meta(XWPFDocument document, String text, RenderStyle style) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(40);
        run(paragraph, text, style.font, style.bodySize, true, false);
    }

    private void body(XWPFDocument document, String text, RenderStyle style) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(style.spaceAfterTwips);
        if (style.lineSpacing > 0f) {
            paragraph.setSpacingBetween(style.lineSpacing);
        }
        run(paragraph, text, style.font, style.bodySize, false, false);
    }

    private void bullet(XWPFDocument document, String text, BigInteger numbering, RenderStyle style) {
        XWPFParagraph paragraph = document.createParagraph();
        if (style.bulletsEnabled) {
            paragraph.setNumID(numbering);
        }
        paragraph.setSpacingAfter(40);
        run(paragraph, text, style.font, style.bodySize, false, false);
    }

    private void run(
            XWPFParagraph paragraph,
            String text,
            String font,
            int size,
            boolean bold,
            boolean underline) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(font);
        run.setFontSize(size);
        run.setBold(bold);
        if (underline) {
            run.setUnderline(UnderlinePatterns.SINGLE);
        }
        run.setText(text);
    }

    private void applyRightTab(XWPFParagraph paragraph, int rightTwips) {
        CTPPr ppr = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        CTTabs tabs = ppr.isSetTabs() ? ppr.getTabs() : ppr.addNewTabs();
        CTTabStop tab = tabs.addNewTab();
        tab.setVal(STTabJc.RIGHT);
        tab.setPos(BigInteger.valueOf(Math.max(rightTwips, 1440)));
    }

    private BigInteger addBulletNumbering(XWPFDocument document, RenderStyle style) {
        CTAbstractNum ctAbstractNum = CTAbstractNum.Factory.newInstance();
        ctAbstractNum.setAbstractNumId(BigInteger.ZERO);
        CTLvl level = ctAbstractNum.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewNumFmt().setVal(STNumberFormat.BULLET);
        level.addNewLvlText().setVal(style.bulletSymbol);
        level.addNewStart().setVal(BigInteger.ONE);
        if (style.bulletLeftTwips > 0 || style.hangingTwips > 0) {
            CTInd ind = level.addNewPPr().addNewInd();
            int hanging = style.hangingTwips > 0 ? style.hangingTwips : 180;
            int left = style.bulletLeftTwips + hanging;
            ind.setLeft(BigInteger.valueOf(left));
            ind.setHanging(BigInteger.valueOf(hanging));
        }
        XWPFAbstractNum abstractNum = new XWPFAbstractNum(ctAbstractNum);
        XWPFNumbering numbering = document.createNumbering();
        BigInteger abstractNumId = numbering.addAbstractNum(abstractNum);
        return numbering.addNum(abstractNumId);
    }

    private boolean hasExperience(ResumeExperience job) {
        return !isBlank(job.getCompany())
                || !isBlank(job.getTitle())
                || hasNonBlank(job.getBullets());
    }

    private boolean hasProject(ResumeProject project) {
        return !isBlank(project.getName())
                || !isBlank(project.getOrganizationOrType())
                || !isBlank(project.getDates())
                || hasNonBlank(project.getBullets())
                || hasNonBlank(project.getLinks());
    }

    private boolean hasEducation(ResumeEducation school) {
        return !isBlank(school.getSchool())
                || !isBlank(school.getDegree())
                || !isBlank(school.getDates())
                || !isBlank(school.getDetails());
    }

    private boolean hasNonBlank(List<String> values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return true;
            }
        }
        return false;
    }

    private void addIfPresent(List<String> values, String value) {
        if (!isBlank(value)) {
            values.add(value.trim());
        }
    }

    private String join(String separator, String... values) {
        List<String> parts = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                addIfPresent(parts, value);
            }
        }
        return String.join(separator, parts);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && needle != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    static String toWordFont(String pdfFont) {
        if (pdfFont == null || pdfFont.isBlank()) {
            return FALLBACK_FONT;
        }
        String normalized = pdfFont.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
        if (normalized.contains("times")) {
            return "Times New Roman";
        }
        if (normalized.contains("georgia")) {
            return "Georgia";
        }
        if (normalized.contains("garamond")) {
            return "Garamond";
        }
        if (normalized.contains("cambria")) {
            return "Cambria";
        }
        if (normalized.contains("calibri")) {
            return "Calibri";
        }
        if (normalized.contains("arial")) {
            return "Arial";
        }
        if (normalized.contains("helvetica") || normalized.contains("sans")) {
            return "Arial";
        }
        if (normalized.contains("verdana")) {
            return "Verdana";
        }
        if (normalized.contains("tahoma")) {
            return "Tahoma";
        }
        if (normalized.contains("courier") || normalized.contains("mono")) {
            return "Courier New";
        }
        return FALLBACK_FONT;
    }

    private static final class RenderStyle {
        private final String font;
        private final String headingFont;
        private final int bodySize;
        private final int nameSize;
        private final int headingSize;
        private final boolean headingBold;
        private final boolean headingUnderline;
        private final boolean headingAllCaps;
        private final ParagraphAlignment headerAlignment;
        private final ParagraphAlignment contactAlignment;
        private final boolean headerRule;
        private final String projectDateAlign;
        private final String educationDateAlign;
        private final String skillPresentation;
        private final boolean bulletsEnabled;
        private final String bulletSymbol;
        private final int bulletLeftTwips;
        private final int hangingTwips;
        private final int spaceAfterTwips;
        private final float lineSpacing;
        private final BigInteger pageWidth;
        private final BigInteger pageHeight;
        private final BigInteger marginTop;
        private final BigInteger marginBottom;
        private final BigInteger marginLeft;
        private final BigInteger marginRight;
        private final int usableWidthTwips;

        private RenderStyle(
                String font,
                String headingFont,
                int bodySize,
                int nameSize,
                int headingSize,
                boolean headingBold,
                boolean headingUnderline,
                boolean headingAllCaps,
                ParagraphAlignment headerAlignment,
                ParagraphAlignment contactAlignment,
                boolean headerRule,
                String projectDateAlign,
                String educationDateAlign,
                String skillPresentation,
                boolean bulletsEnabled,
                String bulletSymbol,
                int bulletLeftTwips,
                int hangingTwips,
                int spaceAfterTwips,
                float lineSpacing,
                BigInteger pageWidth,
                BigInteger pageHeight,
                BigInteger marginTop,
                BigInteger marginBottom,
                BigInteger marginLeft,
                BigInteger marginRight) {
            this.font = font;
            this.headingFont = headingFont;
            this.bodySize = bodySize;
            this.nameSize = nameSize;
            this.headingSize = headingSize;
            this.headingBold = headingBold;
            this.headingUnderline = headingUnderline;
            this.headingAllCaps = headingAllCaps;
            this.headerAlignment = headerAlignment;
            this.contactAlignment = contactAlignment;
            this.headerRule = headerRule;
            this.projectDateAlign = projectDateAlign;
            this.educationDateAlign = educationDateAlign;
            this.skillPresentation = skillPresentation;
            this.bulletsEnabled = bulletsEnabled;
            this.bulletSymbol = bulletSymbol;
            this.bulletLeftTwips = bulletLeftTwips;
            this.hangingTwips = hangingTwips;
            this.spaceAfterTwips = spaceAfterTwips;
            this.lineSpacing = lineSpacing;
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
            this.marginTop = marginTop;
            this.marginBottom = marginBottom;
            this.marginLeft = marginLeft;
            this.marginRight = marginRight;
            this.usableWidthTwips = pageWidth.intValue() - marginLeft.intValue() - marginRight.intValue();
        }

        private static RenderStyle from(LayoutProfile profile) {
            if (profile == null || !profile.isHighOrMedium()) {
                return fallback();
            }
            profile.normalize();
            Body body = profile.getBody();
            SectionHeading heading = profile.getSectionHeading();
            Bullet bullet = profile.getBullet();
            SectionLayout layout = profile.getSectionLayout();
            if (body == null || body.getFontSizePt() <= 0f) {
                return fallback();
            }
            String font = toWordFont(firstNonBlank(body.getFontFamily(), profile.getBaseFontFamily()));
            String headingFont = toWordFont(firstNonBlank(
                    heading == null ? "" : heading.getFontFamily(),
                    font
            ));
            int bodySize = clampSize(Math.round(body.getFontSizePt()), 9, 14);
            int headingSize = heading == null || heading.getFontSizePt() <= 0f
                    ? Math.min(bodySize + 1, 16)
                    : clampSize(Math.round(heading.getFontSizePt()), bodySize, 18);
            int nameSize = clampSize(Math.round(Math.max(16f, bodySize * 1.6f)), 14, 22);
            boolean headingBold = heading == null || heading.isBold();
            boolean headingUnderline = heading != null && heading.isUnderline();
            boolean headingAllCaps = heading == null || heading.isAllCaps();
            ParagraphAlignment headerAlign = LayoutProfile.ALIGN_CENTER.equals(profile.getHeaderAlignment())
                    ? ParagraphAlignment.CENTER
                    : ParagraphAlignment.LEFT;
            ParagraphAlignment contactAlign = LayoutProfile.ALIGN_CENTER.equals(profile.getContactAlignment())
                    ? ParagraphAlignment.CENTER
                    : ParagraphAlignment.LEFT;
            String projectDates = layout == null ? LayoutProfile.DATE_INLINE : layout.getProjectHeaderDateAlignment();
            String educationDates = layout == null ? LayoutProfile.DATE_INLINE : layout.getEducationDateAlignment();
            String skills = layout == null ? LayoutProfile.SKILL_FLAT : layout.getSkillPresentation();
            boolean bulletsEnabled = bullet == null || bullet.isEnabled();
            String symbol = bullet == null || isBlankStatic(bullet.getSymbol()) ? "•" : bullet.getSymbol();
            int leftTwips = ptsToTwips(bullet == null ? 0f : bullet.getLeftIndentPt());
            int hangingTwips = ptsToTwips(bullet == null || bullet.getHangingIndentPt() <= 0f
                    ? 12f
                    : bullet.getHangingIndentPt());
            int spaceAfter = ptsToTwips(body.getSpaceAfterPt() > 0f ? body.getSpaceAfterPt() : 6f);
            float lineSpacing = body.getLineSpacing() > 0f ? body.getLineSpacing() : 1.15f;
            boolean a4 = LayoutProfile.PAGE_A4.equals(profile.getPageSize());
            BigInteger pageW = a4 ? A4_WIDTH : LETTER_WIDTH;
            BigInteger pageH = a4 ? A4_HEIGHT : LETTER_HEIGHT;
            return new RenderStyle(
                    font,
                    headingFont,
                    bodySize,
                    nameSize,
                    headingSize,
                    headingBold,
                    headingUnderline,
                    headingAllCaps,
                    headerAlign,
                    contactAlign,
                    profile.isHasHeaderRule(),
                    projectDates,
                    educationDates,
                    skills,
                    bulletsEnabled,
                    symbol,
                    leftTwips,
                    hangingTwips,
                    Math.max(spaceAfter, 40),
                    lineSpacing,
                    pageW,
                    pageH,
                    marginTwips(profile.getMarginTopPt()),
                    marginTwips(profile.getMarginBottomPt()),
                    marginTwips(profile.getMarginLeftPt()),
                    marginTwips(profile.getMarginRightPt())
            );
        }

        private static RenderStyle fallback() {
            return new RenderStyle(
                    FALLBACK_FONT,
                    FALLBACK_FONT,
                    11,
                    18,
                    12,
                    true,
                    false,
                    true,
                    ParagraphAlignment.CENTER,
                    ParagraphAlignment.CENTER,
                    false,
                    LayoutProfile.DATE_INLINE,
                    LayoutProfile.DATE_INLINE,
                    LayoutProfile.SKILL_FLAT,
                    true,
                    "•",
                    0,
                    0,
                    80,
                    1.0f,
                    LETTER_WIDTH,
                    LETTER_HEIGHT,
                    FALLBACK_MARGIN,
                    FALLBACK_MARGIN,
                    FALLBACK_MARGIN,
                    FALLBACK_MARGIN
            );
        }

        private static String firstNonBlank(String first, String second) {
            if (first != null && !first.isBlank()) {
                return first;
            }
            return second == null ? "" : second;
        }

        private static boolean isBlankStatic(String value) {
            return value == null || value.isBlank();
        }

        private static int clampSize(int size, int min, int max) {
            return Math.max(min, Math.min(max, size));
        }

        private static int ptsToTwips(float pt) {
            if (pt <= 0f) {
                return 0;
            }
            return Math.round(pt * 20f);
        }

        private static BigInteger marginTwips(float pt) {
            if (pt < 24f) {
                return FALLBACK_MARGIN;
            }
            return BigInteger.valueOf(Math.round(Math.min(pt, 144f) * 20f));
        }
    }
}
