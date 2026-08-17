package aijob.util;

import aijob.dto.PdfLayout;
import aijob.dto.PdfLayout.HorizontalRule;
import aijob.dto.ResumeTextBlock;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class PdfLayoutExtractor {

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+?\\d{1,3}[-.\\s]?)?(?:\\(?\\d{3}\\)?[-.\\s]?)\\d{3}[-.\\s]?\\d{4}"
    );
    private static final Pattern URL = Pattern.compile(
            "(https?://\\S+|www\\.\\S+|linkedin\\.com/\\S+|github\\.com/\\S+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DATE_RANGE = Pattern.compile(
            "((?:19|20)\\d{2})\\s*(?:[-–—]|to)\\s*((?:19|20)\\d{2}|present|current|now)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BULLET = Pattern.compile(
            "^[\\u2022\\u25CF\\u25E6\\u2043\\-*·•●]\\s+"
    );

    public PdfLayout extract(Path filePath) {
        if (filePath == null || !filePath.toFile().isFile()) {
            return PdfLayout.imageOnly();
        }
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            GlyphStripper stripper = new GlyphStripper();
            stripper.setSortByPosition(true);
            stripper.getText(document);
            if (stripper.glyphs.isEmpty()) {
                return PdfLayout.imageOnly();
            }
            List<HorizontalRule> rules = detectHorizontalRules(document);
            return buildLayout(stripper.glyphs, rules);
        } catch (IOException e) {
            return PdfLayout.imageOnly();
        }
    }

    private PdfLayout buildLayout(List<Glyph> glyphs, List<HorizontalRule> rules) {
        float pageWidth = glyphs.get(0).pageWidth;
        float pageHeight = glyphs.get(0).pageHeight;
        List<Line> lines = clusterLines(glyphs);
        assignColumns(lines, pageWidth);
        List<Line> ordered = readingOrder(lines);
        List<ResumeTextBlock> blocks = clusterBlocks(ordered);
        List<HorizontalRule> allRules = new ArrayList<>(rules == null ? List.of() : rules);
        allRules.addAll(underscoreRules(ordered));
        markUnderlines(blocks, allRules);
        return PdfLayout.ofBlocks(pageWidth, pageHeight, blocks, allRules);
    }

    private List<HorizontalRule> detectHorizontalRules(PDDocument document) {
        List<HorizontalRule> rules = new ArrayList<>();
        int pageNo = 0;
        for (PDPage page : document.getPages()) {
            pageNo++;
            try {
                HorizontalRuleEngine engine = new HorizontalRuleEngine(page, pageNo);
                engine.processPage(page);
                rules.addAll(engine.rules);
            } catch (IOException | RuntimeException ignored) {
                // layout profile degrades without vector rules
            }
        }
        return rules;
    }

    private List<HorizontalRule> underscoreRules(List<Line> lines) {
        List<HorizontalRule> rules = new ArrayList<>();
        for (Line line : lines) {
            String stripped = line.text.replace(" ", "");
            if (stripped.length() < 8) {
                continue;
            }
            if (!stripped.matches("[_\\-–—]{8,}")) {
                continue;
            }
            HorizontalRule rule = new HorizontalRule();
            rule.setPage(line.page);
            rule.setX(line.x);
            rule.setY(line.y);
            rule.setWidth(Math.max(line.width, 40f));
            rules.add(rule);
        }
        return rules;
    }

    private void markUnderlines(List<ResumeTextBlock> blocks, List<HorizontalRule> rules) {
        if (blocks == null || rules == null) {
            return;
        }
        for (ResumeTextBlock block : blocks) {
            for (HorizontalRule rule : rules) {
                if (rule.getPage() != block.getPage()) {
                    continue;
                }
            if (rule.getY() < block.getY() - 1f || rule.getY() > block.getY() + block.getHeight() + 14f) {
                continue;
            }
                float ruleRight = rule.getX() + rule.getWidth();
                float blockRight = block.getX() + block.getWidth();
                if (rule.getX() <= blockRight && ruleRight >= block.getX()
                        && rule.getWidth() >= Math.min(block.getWidth() * 0.5f, 40f)) {
                    block.setUnderline(true);
                    break;
                }
            }
        }
    }

    private List<Line> clusterLines(List<Glyph> glyphs) {
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator
                .comparingInt((Glyph g) -> g.page)
                .thenComparingDouble((Glyph g) -> g.y)
                .thenComparingDouble((Glyph g) -> g.x));

        List<Line> lines = new ArrayList<>();
        for (Glyph glyph : sorted) {
            Line line = findLine(lines, glyph);
            if (line == null) {
                line = new Line(glyph.page, glyph.y, glyph.pageWidth, glyph.pageHeight);
                lines.add(line);
            }
            line.add(glyph);
        }
        lines.forEach(Line::finish);
        return lines.stream()
                .filter(line -> !line.text.isBlank())
                .toList();
    }

    private Line findLine(List<Line> lines, Glyph glyph) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            Line line = lines.get(i);
            if (line.page != glyph.page) {
                break;
            }
            float tolerance = Math.max(2f, Math.max(line.height, glyph.height) * 0.6f);
            if (Math.abs(line.y - glyph.y) <= tolerance) {
                float columnGap = glyph.pageWidth * 0.12f;
                if (glyph.x - line.maxX() > Math.max(24f, columnGap)) {
                    continue;
                }
                return line;
            }
        }
        return null;
    }

    private void assignColumns(List<Line> lines, float pageWidth) {
        if (lines.isEmpty()) {
            return;
        }
        List<Float> lefts = lines.stream()
                .map(line -> line.x)
                .sorted()
                .toList();
        float largestGap = 0f;
        float splitAt = -1f;
        for (int i = 1; i < lefts.size(); i++) {
            float gap = lefts.get(i) - lefts.get(i - 1);
            if (gap > largestGap) {
                largestGap = gap;
                splitAt = (lefts.get(i) + lefts.get(i - 1)) / 2f;
            }
        }
        boolean twoColumns = largestGap > pageWidth * 0.15f && splitAt > 0f;
        for (Line line : lines) {
            if (!twoColumns) {
                line.columnIndex = 0;
                continue;
            }
            boolean spanning = line.width > pageWidth * 0.55f
                    || (line.x < splitAt && line.x + line.width > splitAt + pageWidth * 0.05f);
            if (spanning) {
                line.columnIndex = -1;
            } else {
                line.columnIndex = line.x < splitAt ? 0 : 1;
            }
        }
    }

    private List<Line> readingOrder(List<Line> lines) {
        List<Line> ordered = new ArrayList<>();
        int page = -1;
        List<Line> pageLines = new ArrayList<>();
        for (Line line : lines) {
            if (page != -1 && line.page != page) {
                ordered.addAll(orderPage(pageLines));
                pageLines.clear();
            }
            page = line.page;
            pageLines.add(line);
        }
        if (!pageLines.isEmpty()) {
            ordered.addAll(orderPage(pageLines));
        }
        return ordered;
    }

    private List<Line> orderPage(List<Line> pageLines) {
        boolean twoColumns = pageLines.stream().anyMatch(line -> line.columnIndex == 1);
        if (!twoColumns) {
            return pageLines.stream()
                    .sorted(Comparator.comparingDouble((Line line) -> line.y).thenComparingDouble(line -> line.x))
                    .toList();
        }
        List<Line> spanning = new ArrayList<>();
        List<Line> left = new ArrayList<>();
        List<Line> right = new ArrayList<>();
        for (Line line : pageLines) {
            if (line.columnIndex < 0) {
                spanning.add(line);
            } else if (line.columnIndex == 0) {
                left.add(line);
            } else {
                right.add(line);
            }
        }
        Comparator<Line> byY = Comparator.comparingDouble((Line line) -> line.y)
                .thenComparingDouble(line -> line.x);
        spanning.sort(byY);
        left.sort(byY);
        right.sort(byY);
        List<Line> ordered = new ArrayList<>(spanning);
        ordered.addAll(left);
        ordered.addAll(right);
        return ordered;
    }

    private List<ResumeTextBlock> clusterBlocks(List<Line> ordered) {
        List<ResumeTextBlock> blocks = new ArrayList<>();
        if (ordered.isEmpty()) {
            return blocks;
        }

        float maxFont = ordered.stream()
                .filter(line -> line.page == 1)
                .map(line -> line.fontSize)
                .max(Float::compare)
                .orElse(0f);

        List<Line> current = new ArrayList<>();
        int indexOnPage = 0;
        int page = ordered.get(0).page;

        for (Line line : ordered) {
            if (current.isEmpty()) {
                current.add(line);
                page = line.page;
                continue;
            }
            Line prev = current.get(current.size() - 1);
            if (shouldSplit(prev, line)) {
                blocks.add(toBlock(current, indexOnPage, maxFont));
                if (line.page != page) {
                    indexOnPage = 0;
                    page = line.page;
                } else {
                    indexOnPage++;
                }
                current = new ArrayList<>();
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            blocks.add(toBlock(current, indexOnPage, maxFont));
        }
        return blocks;
    }

    private boolean shouldSplit(Line prev, Line next) {
        if (prev.page != next.page) {
            return true;
        }
        if (prev.columnIndex != next.columnIndex) {
            return true;
        }
        float gap = next.y - (prev.y + prev.height);
        if (gap > Math.max(prev.height, next.height) * 1.35f) {
            return true;
        }
        if (Math.abs(next.fontSize - prev.fontSize) >= 1.5f) {
            return true;
        }
        if (BULLET.matcher(next.text).find() && !BULLET.matcher(prev.text).find()) {
            return true;
        }
        return Math.abs(next.x - prev.x) > 36f && next.columnIndex == prev.columnIndex;
    }

    private ResumeTextBlock toBlock(List<Line> lines, int indexOnPage, float maxFont) {
        Line first = lines.get(0);
        float minX = first.x;
        float minY = first.y;
        float maxX = first.x + first.width;
        float maxY = first.y + first.height;
        float fontSum = 0f;
        int boldCount = 0;
        int glyphCount = 0;
        float maxGap = 0f;
        float contentX = first.contentX > 0f ? first.contentX : first.x;
        Map<String, Integer> families = new HashMap<>();
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            minX = Math.min(minX, line.x);
            minY = Math.min(minY, line.y);
            maxX = Math.max(maxX, line.x + line.width);
            maxY = Math.max(maxY, line.y + line.height);
            fontSum += line.fontSize;
            boldCount += line.boldCount;
            glyphCount += line.glyphCount;
            maxGap = Math.max(maxGap, line.maxInternalGap);
            contentX = Math.min(contentX, line.contentX > 0f ? line.contentX : line.x);
            if (line.fontFamily != null && !line.fontFamily.isBlank()) {
                families.merge(line.fontFamily, line.glyphCount, Integer::sum);
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(line.text);
        }

        ResumeTextBlock block = new ResumeTextBlock();
        block.setId("b-p" + first.page + "-" + indexOnPage);
        block.setPage(first.page);
        block.setX(minX);
        block.setY(minY);
        block.setWidth(maxX - minX);
        block.setHeight(maxY - minY);
        block.setFontSize(fontSum / lines.size());
        block.setFontFamily(modalFamily(families));
        block.setBold(glyphCount > 0 && boldCount * 2 >= glyphCount);
        block.setColumnIndex(Math.max(first.columnIndex, 0));
        block.setMaxInternalGap(maxGap);
        block.setContentX(contentX);
        block.setText(text.toString().trim());
        block.setFeatures(detectFeatures(block, maxFont));
        block.setConfidence(confidenceFor(block.getFeatures()));
        block.normalize();
        return block;
    }

    private String modalFamily(Map<String, Integer> families) {
        String best = "";
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : families.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    private List<String> detectFeatures(ResumeTextBlock block, float maxFont) {
        List<String> features = new ArrayList<>();
        String text = block.getText();
        if (EMAIL.matcher(text).find()) {
            features.add(ResumeTextBlock.FEATURE_EMAIL);
        }
        if (PHONE.matcher(text).find()) {
            features.add(ResumeTextBlock.FEATURE_PHONE);
        }
        if (URL.matcher(text).find()) {
            features.add(ResumeTextBlock.FEATURE_URL);
        }
        if (DATE_RANGE.matcher(text).find()) {
            features.add(ResumeTextBlock.FEATURE_DATE_RANGE);
        }
        if (BULLET.matcher(text).find()) {
            features.add(ResumeTextBlock.FEATURE_BULLET);
        }
        if (isAllCaps(text)) {
            features.add(ResumeTextBlock.FEATURE_ALL_CAPS);
        }
        if (looksLikeSectionHeading(text, block, maxFont)) {
            features.add(ResumeTextBlock.FEATURE_SECTION_HEADING);
        }
        if (maxFont > 0 && block.getFontSize() >= maxFont - 0.4f && block.getPage() == 1) {
            features.add(ResumeTextBlock.FEATURE_LARGE_FONT);
        }
        if (text.chars().filter(ch -> ch == ',').count() >= 2) {
            features.add(ResumeTextBlock.FEATURE_COMMA_LIST);
        }
        return features;
    }

    private String confidenceFor(List<String> features) {
        if (features.contains(ResumeTextBlock.FEATURE_EMAIL)
                || features.contains(ResumeTextBlock.FEATURE_PHONE)
                || features.contains(ResumeTextBlock.FEATURE_URL)) {
            return ResumeTextBlock.CONFIDENCE_HIGH;
        }
        if (features.contains(ResumeTextBlock.FEATURE_LARGE_FONT)) {
            return ResumeTextBlock.CONFIDENCE_MEDIUM;
        }
        return ResumeTextBlock.CONFIDENCE_LOW;
    }

    private boolean isAllCaps(String text) {
        String letters = text.replaceAll("[^A-Za-z]", "");
        if (letters.length() < 4) {
            return false;
        }
        return letters.equals(letters.toUpperCase(Locale.ROOT));
    }

    private boolean looksLikeSectionHeading(String text, ResumeTextBlock block, float maxFont) {
        String line = text.contains("\n") ? text.substring(0, text.indexOf('\n')).trim() : text.trim();
        if (line.length() < 4 || line.length() > 48) {
            return false;
        }
        if (EMAIL.matcher(line).find() || PHONE.matcher(line).find() || URL.matcher(line).find()) {
            return false;
        }
        boolean allCaps = isAllCaps(line);
        boolean larger = maxFont > 0f && block.getFontSize() >= maxFont - 1.5f && block.getFontSize() < maxFont - 0.4f;
        return allCaps && (block.isBold() || block.getFontSize() >= 11f || larger);
    }

    private static final class GlyphStripper extends PDFTextStripper {

        private final List<Glyph> glyphs = new ArrayList<>();

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            int page = getCurrentPageNo();
            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                Glyph glyph = new Glyph();
                glyph.page = page;
                glyph.x = position.getXDirAdj();
                glyph.y = position.getYDirAdj();
                glyph.width = position.getWidthDirAdj();
                glyph.height = position.getHeightDir();
                glyph.fontSize = position.getFontSizeInPt();
                if (glyph.fontSize <= 0f) {
                    glyph.fontSize = position.getFontSize();
                }
                glyph.bold = isBold(position.getFont());
                glyph.fontFamily = fontFamilyOf(position.getFont());
                glyph.unicode = unicode;
                glyph.pageWidth = position.getPageWidth();
                glyph.pageHeight = position.getPageHeight();
                glyphs.add(glyph);
            }
        }
    }

    private static String fontFamilyOf(PDFont font) {
        if (font == null) {
            return "";
        }
        try {
            PDFontDescriptor descriptor = font.getFontDescriptor();
            if (descriptor != null) {
                if (descriptor.getFontFamily() != null && !descriptor.getFontFamily().isBlank()) {
                    return descriptor.getFontFamily().trim();
                }
                if (descriptor.getFontName() != null && !descriptor.getFontName().isBlank()) {
                    return descriptor.getFontName().trim();
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the PostScript name
        }
        return font.getName() == null ? "" : font.getName();
    }

    private static boolean isBold(PDFont font) {
        if (font == null) {
            return false;
        }
        String name = font.getName();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("bold") || lower.contains("black") || lower.contains("heavy")) {
                return true;
            }
        }
        try {
            PDFontDescriptor descriptor = font.getFontDescriptor();
            if (descriptor == null) {
                return false;
            }
            if (descriptor.isForceBold()) {
                return true;
            }
            return descriptor.getFontWeight() >= 700;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static final class Glyph {
        int page;
        float x;
        float y;
        float width;
        float height;
        float fontSize;
        boolean bold;
        String fontFamily = "";
        String unicode;
        float pageWidth;
        float pageHeight;
    }

    private static final class Line {
        final int page;
        final float pageWidth;
        final float pageHeight;
        float y;
        float x;
        float width;
        float height;
        float fontSize;
        String fontFamily = "";
        int columnIndex;
        int boldCount;
        int glyphCount;
        float maxInternalGap;
        float contentX;
        String text = "";
        private final List<Glyph> glyphs = new ArrayList<>();

        private Line(int page, float y, float pageWidth, float pageHeight) {
            this.page = page;
            this.y = y;
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
        }

        private void add(Glyph glyph) {
            glyphs.add(glyph);
            y = ((y * (glyphs.size() - 1)) + glyph.y) / glyphs.size();
            height = Math.max(height, glyph.height);
        }

        private float maxX() {
            float max = Float.NEGATIVE_INFINITY;
            for (Glyph glyph : glyphs) {
                max = Math.max(max, glyph.x + glyph.width);
            }
            return max;
        }

        private void finish() {
            glyphs.sort(Comparator.comparingDouble((Glyph glyph) -> glyph.x));
            StringBuilder builder = new StringBuilder();
            Glyph previous = null;
            float fontSum = 0f;
            float minX = Float.MAX_VALUE;
            float maxX = 0f;
            float maxH = 0f;
            float maxGap = 0f;
            Map<String, Integer> families = new HashMap<>();
            for (Glyph glyph : glyphs) {
                fontSum += glyph.fontSize;
                if (glyph.bold) {
                    boldCount++;
                }
                glyphCount++;
                minX = Math.min(minX, glyph.x);
                maxX = Math.max(maxX, glyph.x + glyph.width);
                maxH = Math.max(maxH, glyph.height);
                if (glyph.fontFamily != null && !glyph.fontFamily.isBlank()) {
                    families.merge(glyph.fontFamily, 1, Integer::sum);
                }
                if (previous != null) {
                    float gap = glyph.x - (previous.x + previous.width);
                    maxGap = Math.max(maxGap, gap);
                    if (gap > Math.max(1.2f, previous.fontSize * 0.18f)
                            && (builder.isEmpty() || builder.charAt(builder.length() - 1) != ' ')) {
                        builder.append(' ');
                    }
                }
                builder.append(glyph.unicode);
                previous = glyph;
            }
            x = minX;
            width = Math.max(0f, maxX - minX);
            height = maxH;
            fontSize = glyphCount == 0 ? 0f : fontSum / glyphCount;
            maxInternalGap = maxGap;
            fontFamily = modalFamily(families);
            contentX = firstContentX();
            text = builder.toString().replaceAll("[ \\t]+", " ").trim();
        }

        private String modalFamily(Map<String, Integer> families) {
            String best = "";
            int bestCount = -1;
            for (Map.Entry<String, Integer> entry : families.entrySet()) {
                if (entry.getValue() > bestCount) {
                    bestCount = entry.getValue();
                    best = entry.getKey();
                }
            }
            return best;
        }

        private float firstContentX() {
            for (Glyph glyph : glyphs) {
                String unicode = glyph.unicode;
                if (unicode == null || unicode.isBlank()) {
                    continue;
                }
                if (unicode.chars().allMatch(Character::isWhitespace)) {
                    continue;
                }
                if (unicode.length() == 1 && "•●◦▪▸-*–—·\u2022\u25CF\u25E6\u2043".indexOf(unicode.charAt(0)) >= 0) {
                    continue;
                }
                return glyph.x;
            }
            return x;
        }
    }

    private static final class HorizontalRuleEngine extends PDFGraphicsStreamEngine {

        private final int pageNo;
        private final float pageHeight;
        private final List<HorizontalRule> rules = new ArrayList<>();
        private final List<Point2D.Float> path = new ArrayList<>();
        private Point2D.Float current;
        private Point2D.Float subpathStart;

        private HorizontalRuleEngine(PDPage page, int pageNo) {
            super(page);
            this.pageNo = pageNo;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            maybeThinRect(p0, p1, p2, p3);
        }

        @Override
        public void drawImage(PDImage pdImage) {
            // icons and photos are ignored for layout profile
        }

        @Override
        public void clip(int windingRule) {
            path.clear();
        }

        @Override
        public void moveTo(float x, float y) {
            Point2D.Float point = transform(x, y);
            current = point;
            subpathStart = point;
            path.add(point);
        }

        @Override
        public void lineTo(float x, float y) {
            Point2D.Float point = transform(x, y);
            if (current != null) {
                maybeHorizontal(current, point);
            }
            current = point;
            path.add(point);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            current = transform(x3, y3);
            path.add(current);
        }

        @Override
        public Point2D getCurrentPoint() {
            return current == null ? new Point2D.Float() : current;
        }

        @Override
        public void closePath() {
            if (current != null && subpathStart != null) {
                maybeHorizontal(current, subpathStart);
            }
            current = subpathStart;
        }

        @Override
        public void endPath() {
            path.clear();
            current = null;
            subpathStart = null;
        }

        @Override
        public void strokePath() {
            path.clear();
            current = null;
            subpathStart = null;
        }

        @Override
        public void fillPath(int windingRule) {
            path.clear();
            current = null;
            subpathStart = null;
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            path.clear();
            current = null;
            subpathStart = null;
        }

        @Override
        public void shadingFill(COSName shadingName) {
            // ignored
        }

        private Point2D.Float transform(float x, float y) {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            return ctm.transformPoint(x, y);
        }

        private void maybeThinRect(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            addThinRect(p0, p1, p2, p3);
            addThinRect(
                    transform((float) p0.getX(), (float) p0.getY()),
                    transform((float) p1.getX(), (float) p1.getY()),
                    transform((float) p2.getX(), (float) p2.getY()),
                    transform((float) p3.getX(), (float) p3.getY())
            );
        }

        private void addThinRect(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            float minX = (float) Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
            float maxX = (float) Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
            float minY = (float) Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
            float maxY = (float) Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
            float width = maxX - minX;
            float height = maxY - minY;
            if (width >= 24f && height <= 8f && height < width / 8f) {
                addRule(minX, (minY + maxY) / 2f, width);
            }
        }

        private void maybeHorizontal(Point2D.Float from, Point2D.Float to) {
            float dx = Math.abs(to.x - from.x);
            float dy = Math.abs(to.y - from.y);
            if (dx >= 24f && dy <= 3.5f) {
                addRule(Math.min(from.x, to.x), (from.y + to.y) / 2f, dx);
            }
        }

        private void addRule(float x, float pdfY, float width) {
            HorizontalRule rule = new HorizontalRule();
            rule.setPage(pageNo);
            rule.setX(x);
            rule.setY(pageHeight - pdfY);
            rule.setWidth(width);
            rules.add(rule);
        }
    }
}
