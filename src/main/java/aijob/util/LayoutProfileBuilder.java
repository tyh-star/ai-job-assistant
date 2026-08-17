package aijob.util;

import aijob.dto.LayoutProfile;
import aijob.dto.LayoutProfile.Body;
import aijob.dto.LayoutProfile.Bullet;
import aijob.dto.LayoutProfile.SectionHeading;
import aijob.dto.LayoutProfile.SectionLayout;
import aijob.dto.PdfLayout;
import aijob.dto.PdfLayout.HorizontalRule;
import aijob.dto.ResumeTextBlock;
import aijob.enums.ResumeExtractionStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LayoutProfileBuilder {

    private static final Pattern DATE_RANGE = Pattern.compile(
            "((?:19|20)\\d{2})\\s*(?:[-–—]|to)\\s*((?:19|20)\\d{2}|present|current|now)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CATEGORY_LINE = Pattern.compile(
            "(?m)^([^\\n:]{2,40}):\\s+\\S"
    );
    private static final Pattern BULLET_GLYPH = Pattern.compile(
            "^[\\u2022\\u25CF\\u25E6\\u2043\\-*·•●]"
    );
    private static final Pattern PROJECT_HEADING = Pattern.compile(
            "PROJECT",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EDUCATION_HEADING = Pattern.compile(
            "EDUCATION",
            Pattern.CASE_INSENSITIVE
    );

    private LayoutProfileBuilder() {
    }

    public static LayoutProfile from(PdfLayout layout) {
        if (layout == null
                || layout.getBlocks() == null
                || layout.getBlocks().isEmpty()
                || ResumeExtractionStatus.IMAGE_ONLY.name().equals(layout.getStatus())) {
            return LayoutProfile.lowConfidence();
        }

        List<ResumeTextBlock> blocks = layout.getBlocks();
        float pageWidth = layout.getPageWidth() > 0f ? layout.getPageWidth() : 612f;
        float pageHeight = layout.getPageHeight() > 0f ? layout.getPageHeight() : 792f;
        List<HorizontalRule> rules = layout.getHorizontalRules() == null
                ? List.of()
                : layout.getHorizontalRules();

        LayoutProfile profile = new LayoutProfile();
        profile.setVersion(LayoutProfile.VERSION);
        profile.setPageSize(detectPageSize(pageWidth, pageHeight));
        applyMargins(profile, blocks, pageWidth, pageHeight);

        FontStat bodyFont = modalBodyFont(blocks);
        profile.setBaseFontFamily(bodyFont.family);
        profile.setBaseFontSizePt(bodyFont.size);

        Body body = new Body();
        body.setFontFamily(bodyFont.family);
        body.setFontSizePt(bodyFont.size);
        body.setLineSpacing(estimateLineSpacing(blocks, bodyFont.size));
        body.setSpaceAfterPt(estimateSpaceAfter(blocks, bodyFont.size));
        profile.setBody(body);

        ResumeTextBlock name = selectLargestTopBlock(blocks, pageHeight);
        profile.setHeaderAlignment(alignmentOf(name, pageWidth));
        ResumeTextBlock contact = selectContactBlock(blocks, pageHeight, name);
        profile.setContactAlignment(alignmentOf(contact != null ? contact : name, pageWidth));
        profile.setHasHeaderRule(hasHeaderRule(rules, name, contact, pageWidth, pageHeight));

        List<ResumeTextBlock> headings = headingCandidates(blocks, bodyFont.size, name);
        profile.setSectionHeading(sectionHeadingFrom(headings, rules, bodyFont, pageHeight));

        profile.setBullet(bulletFrom(blocks, profile.getMarginLeftPt()));

        SectionLayout sectionLayout = new SectionLayout();
        sectionLayout.setProjectHeaderDateAlignment(
                dateAlignment(blocks, headings, pageWidth, true));
        sectionLayout.setEducationDateAlignment(
                dateAlignment(blocks, headings, pageWidth, false));
        sectionLayout.setSkillPresentation(skillPresentation(blocks));
        sectionLayout.setColumnCount(columnCount(blocks, pageWidth));
        profile.setSectionLayout(sectionLayout);

        profile.setConfidence(confidenceFor(profile, blocks, headings));
        if (!LayoutProfile.CONFIDENCE_HIGH.equals(profile.getConfidence())
                && !LayoutProfile.CONFIDENCE_MEDIUM.equals(profile.getConfidence())) {
            sectionLayout.setColumnCount(1);
        }
        profile.normalize();
        return profile;
    }

    private static String detectPageSize(float width, float height) {
        if (near(width, 612f, 8f) && near(height, 792f, 8f)) {
            return LayoutProfile.PAGE_LETTER;
        }
        if (near(width, 595f, 8f) && near(height, 842f, 8f)) {
            return LayoutProfile.PAGE_A4;
        }
        return LayoutProfile.PAGE_UNKNOWN;
    }

    private static boolean near(float value, float target, float tolerance) {
        return Math.abs(value - target) <= tolerance;
    }

    private static void applyMargins(
            LayoutProfile profile,
            List<ResumeTextBlock> blocks,
            float pageWidth,
            float pageHeight) {
        float minX = pageWidth;
        float minY = pageHeight;
        float maxX = 0f;
        float maxY = 0f;
        boolean found = false;
        for (ResumeTextBlock block : blocks) {
            if (block.getPage() != 1 || block.getWidth() < 8f) {
                continue;
            }
            found = true;
            minX = Math.min(minX, block.getX());
            minY = Math.min(minY, block.getY());
            maxX = Math.max(maxX, block.getX() + block.getWidth());
            maxY = Math.max(maxY, block.getY() + block.getHeight());
        }
        if (!found) {
            return;
        }
        profile.setMarginLeftPt(clampMargin(minX));
        profile.setMarginTopPt(clampMargin(minY));
        profile.setMarginRightPt(clampMargin(pageWidth - maxX));
        profile.setMarginBottomPt(clampMargin(pageHeight - maxY));
    }

    private static float clampMargin(float value) {
        if (Float.isNaN(value) || value < 0f) {
            return 0f;
        }
        return Math.min(value, 144f);
    }

    private static FontStat modalBodyFont(List<ResumeTextBlock> blocks) {
        Map<String, Integer> familyCounts = new HashMap<>();
        Map<Integer, Integer> sizeCounts = new HashMap<>();
        float maxFont = 0f;
        for (ResumeTextBlock block : blocks) {
            maxFont = Math.max(maxFont, block.getFontSize());
        }
        for (ResumeTextBlock block : blocks) {
            if (block.getFontSize() <= 0f) {
                continue;
            }
            if (maxFont > 0f && block.getFontSize() >= maxFont - 0.4f
                    && hasFeature(block, ResumeTextBlock.FEATURE_LARGE_FONT)) {
                continue;
            }
            if (hasFeature(block, ResumeTextBlock.FEATURE_SECTION_HEADING)
                    || hasFeature(block, ResumeTextBlock.FEATURE_ALL_CAPS)
                    && block.getText() != null
                    && block.getText().length() < 48) {
                continue;
            }
            int sizeKey = Math.round(block.getFontSize() * 2f);
            sizeCounts.merge(sizeKey, Math.max(1, block.getText() == null ? 1 : block.getText().length()), Integer::sum);
            String family = block.getFontFamily() == null ? "" : block.getFontFamily().trim();
            if (!family.isBlank()) {
                familyCounts.merge(family, Math.max(1, block.getText().length()), Integer::sum);
            }
        }
        float size = 0f;
        int bestSizeCount = -1;
        for (Map.Entry<Integer, Integer> entry : sizeCounts.entrySet()) {
            if (entry.getValue() > bestSizeCount) {
                bestSizeCount = entry.getValue();
                size = entry.getKey() / 2f;
            }
        }
        String family = "";
        int bestFamilyCount = -1;
        for (Map.Entry<String, Integer> entry : familyCounts.entrySet()) {
            if (entry.getValue() > bestFamilyCount) {
                bestFamilyCount = entry.getValue();
                family = entry.getKey();
            }
        }
        return new FontStat(family, size);
    }

    private static float estimateLineSpacing(List<ResumeTextBlock> blocks, float bodySize) {
        List<Float> ratios = new ArrayList<>();
        List<ResumeTextBlock> ordered = pageBlocks(blocks, 1);
        ordered.sort(Comparator.comparingDouble(ResumeTextBlock::getY));
        for (int i = 1; i < ordered.size(); i++) {
            ResumeTextBlock prev = ordered.get(i - 1);
            ResumeTextBlock next = ordered.get(i);
            if (Math.abs(next.getX() - prev.getX()) > 36f) {
                continue;
            }
            float gap = next.getY() - prev.getY();
            float size = prev.getFontSize() > 0f ? prev.getFontSize() : bodySize;
            if (size <= 0f || gap <= 0f || gap > size * 3.5f) {
                continue;
            }
            ratios.add(gap / size);
        }
        if (ratios.isEmpty()) {
            return 1.15f;
        }
        ratios.sort(Float::compare);
        return round1(ratios.get(ratios.size() / 2));
    }

    private static float estimateSpaceAfter(List<ResumeTextBlock> blocks, float bodySize) {
        float size = bodySize > 0f ? bodySize : 11f;
        return round1(Math.max(2f, size * 0.35f));
    }

    private static ResumeTextBlock selectLargestTopBlock(List<ResumeTextBlock> blocks, float pageHeight) {
        float topBand = pageHeight * 0.32f;
        ResumeTextBlock best = null;
        for (ResumeTextBlock block : blocks) {
            if (block.getPage() != 1 || block.getY() > topBand) {
                continue;
            }
            if (hasFeature(block, ResumeTextBlock.FEATURE_EMAIL)
                    || hasFeature(block, ResumeTextBlock.FEATURE_PHONE)
                    || hasFeature(block, ResumeTextBlock.FEATURE_URL)) {
                continue;
            }
            if (best == null
                    || block.getFontSize() > best.getFontSize() + 0.2f
                    || (Math.abs(block.getFontSize() - best.getFontSize()) <= 0.2f
                    && block.getY() < best.getY())) {
                best = block;
            }
        }
        return best;
    }

    private static ResumeTextBlock selectContactBlock(
            List<ResumeTextBlock> blocks,
            float pageHeight,
            ResumeTextBlock name) {
        float topBand = pageHeight * 0.36f;
        ResumeTextBlock best = null;
        for (ResumeTextBlock block : blocks) {
            if (block.getPage() != 1 || block.getY() > topBand) {
                continue;
            }
            if (name != null && name.getId() != null && name.getId().equals(block.getId())) {
                continue;
            }
            boolean contact = hasFeature(block, ResumeTextBlock.FEATURE_EMAIL)
                    || hasFeature(block, ResumeTextBlock.FEATURE_PHONE)
                    || hasFeature(block, ResumeTextBlock.FEATURE_URL);
            if (!contact) {
                continue;
            }
            if (best == null || block.getY() < best.getY()) {
                best = block;
            }
        }
        return best;
    }

    private static String alignmentOf(ResumeTextBlock block, float pageWidth) {
        if (block == null || pageWidth <= 0f) {
            return LayoutProfile.ALIGN_LEFT;
        }
        float centerX = block.getX() + block.getWidth() / 2f;
        float pageCenter = pageWidth / 2f;
        if (Math.abs(centerX - pageCenter) <= pageWidth * 0.08f && block.getX() > pageWidth * 0.18f) {
            return LayoutProfile.ALIGN_CENTER;
        }
        return LayoutProfile.ALIGN_LEFT;
    }

    private static boolean hasHeaderRule(
            List<HorizontalRule> rules,
            ResumeTextBlock name,
            ResumeTextBlock contact,
            float pageWidth,
            float pageHeight) {
        float minY = 0f;
        if (name != null) {
            minY = name.getY() + name.getHeight() * 0.3f;
        }
        if (contact != null) {
            minY = Math.max(minY, contact.getY());
        }
        float maxY = pageHeight * 0.34f;
        float minWidth = pageWidth * 0.32f;
        for (HorizontalRule rule : rules) {
            if (rule.getPage() != 1 || rule.getWidth() < minWidth) {
                continue;
            }
            for (float y : ruleYs(rule, pageHeight)) {
                if (y >= minY - 6f && y <= maxY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<ResumeTextBlock> headingCandidates(
            List<ResumeTextBlock> blocks,
            float bodySize,
            ResumeTextBlock name) {
        List<ResumeTextBlock> headings = new ArrayList<>();
        for (ResumeTextBlock block : blocks) {
            if (name != null && name.getId() != null && name.getId().equals(block.getId())) {
                continue;
            }
            if (hasFeature(block, ResumeTextBlock.FEATURE_EMAIL)
                    || hasFeature(block, ResumeTextBlock.FEATURE_PHONE)
                    || hasFeature(block, ResumeTextBlock.FEATURE_DATE_RANGE)
                    || hasFeature(block, ResumeTextBlock.FEATURE_BULLET)) {
                continue;
            }
            String text = firstLine(block.getText());
            if (text.length() < 4 || text.length() > 48) {
                continue;
            }
            boolean allCaps = hasFeature(block, ResumeTextBlock.FEATURE_ALL_CAPS);
            boolean marked = hasFeature(block, ResumeTextBlock.FEATURE_SECTION_HEADING);
            boolean larger = bodySize > 0f && block.getFontSize() >= bodySize + 0.6f;
            if (marked || allCaps || (block.isBold() && larger)) {
                headings.add(block);
            }
        }
        return headings;
    }

    private static SectionHeading sectionHeadingFrom(
            List<ResumeTextBlock> headings,
            List<HorizontalRule> rules,
            FontStat bodyFont,
            float pageHeight) {
        SectionHeading heading = new SectionHeading();
        if (headings.isEmpty()) {
            heading.setFontFamily(bodyFont.family);
            heading.setFontSizePt(bodyFont.size > 0f ? bodyFont.size + 1f : 12f);
            heading.setBold(true);
            heading.setAllCaps(true);
            return heading;
        }
        Map<String, Integer> families = new HashMap<>();
        Map<Integer, Integer> sizes = new HashMap<>();
        int bold = 0;
        int allCaps = 0;
        int underlined = 0;
        for (ResumeTextBlock block : headings) {
            String family = block.getFontFamily() == null ? "" : block.getFontFamily().trim();
            if (!family.isBlank()) {
                families.merge(family, 1, Integer::sum);
            }
            if (block.getFontSize() > 0f) {
                sizes.merge(Math.round(block.getFontSize() * 2f), 1, Integer::sum);
            }
            if (block.isBold()) {
                bold++;
            }
            if (hasFeature(block, ResumeTextBlock.FEATURE_ALL_CAPS)) {
                allCaps++;
            }
            if (block.isUnderline() || hasUnderlineRule(block, rules, pageHeight)) {
                underlined++;
            }
        }
        heading.setFontFamily(maxKey(families, bodyFont.family));
        float size = bodyFont.size > 0f ? bodyFont.size + 1f : 12f;
        int best = -1;
        for (Map.Entry<Integer, Integer> entry : sizes.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                size = entry.getKey() / 2f;
            }
        }
        heading.setFontSizePt(size);
        heading.setBold(bold * 2 >= headings.size());
        heading.setAllCaps(allCaps * 2 >= headings.size());
        heading.setUnderline(underlined * 2 >= headings.size() || underlined > 0 && underlined + 1 >= headings.size());
        return heading;
    }

    private static boolean hasUnderlineRule(
            ResumeTextBlock block,
            List<HorizontalRule> rules,
            float pageHeight) {
        for (HorizontalRule rule : rules) {
            if (rule.getPage() != block.getPage()) {
                continue;
            }
            for (float y : ruleYs(rule, pageHeight)) {
                if (y < block.getY() - 1f || y > block.getY() + block.getHeight() + 14f) {
                    continue;
                }
                float ruleRight = rule.getX() + rule.getWidth();
                float blockRight = block.getX() + block.getWidth();
                boolean overlap = rule.getX() <= blockRight && ruleRight >= block.getX();
                if (overlap && rule.getWidth() >= Math.min(block.getWidth() * 0.45f, 36f)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float[] ruleYs(HorizontalRule rule, float pageHeight) {
        return new float[] {rule.getY(), pageHeight - rule.getY()};
    }

    private static Bullet bulletFrom(List<ResumeTextBlock> blocks, float marginLeft) {
        Bullet bullet = new Bullet();
        ResumeTextBlock sample = null;
        for (ResumeTextBlock block : blocks) {
            if (hasFeature(block, ResumeTextBlock.FEATURE_BULLET)) {
                sample = block;
                break;
            }
        }
        if (sample == null) {
            bullet.setEnabled(true);
            bullet.setSymbol("•");
            bullet.setLeftIndentPt(18f);
            bullet.setHangingIndentPt(12f);
            return bullet;
        }
        String text = firstLine(sample.getText());
        Matcher glyph = BULLET_GLYPH.matcher(text);
        bullet.setEnabled(true);
        bullet.setSymbol(glyph.find() ? glyph.group() : "•");
        float left = sample.getX() - marginLeft;
        if (left < 0f) {
            left = 0f;
        }
        bullet.setLeftIndentPt(round1(left));
        float hanging = sample.getContentX() > sample.getX()
                ? sample.getContentX() - sample.getX()
                : 12f;
        if (hanging < 8f) {
            hanging = 12f;
        }
        bullet.setHangingIndentPt(round1(Math.min(hanging, 36f)));
        return bullet;
    }

    private static String dateAlignment(
            List<ResumeTextBlock> blocks,
            List<ResumeTextBlock> headings,
            float pageWidth,
            boolean projects) {
        int right = 0;
        int inline = 0;
        int left = 0;
        for (ResumeTextBlock block : blocks) {
            if (!hasFeature(block, ResumeTextBlock.FEATURE_DATE_RANGE) && !containsDate(block.getText())) {
                continue;
            }
            if (!matchesSection(block, headings, projects)) {
                continue;
            }
            String vote = voteDateAlignment(block, blocks, pageWidth);
            switch (vote) {
                case LayoutProfile.DATE_RIGHT -> right++;
                case LayoutProfile.DATE_LEFT -> left++;
                default -> inline++;
            }
        }
        if (right + inline + left == 0) {
            for (ResumeTextBlock block : blocks) {
                if (!hasFeature(block, ResumeTextBlock.FEATURE_DATE_RANGE) && !containsDate(block.getText())) {
                    continue;
                }
                String vote = voteDateAlignment(block, blocks, pageWidth);
                switch (vote) {
                    case LayoutProfile.DATE_RIGHT -> right++;
                    case LayoutProfile.DATE_LEFT -> left++;
                    default -> inline++;
                }
            }
        }
        if (right >= inline && right >= left && right > 0) {
            return LayoutProfile.DATE_RIGHT;
        }
        if (left > inline && left > right) {
            return LayoutProfile.DATE_LEFT;
        }
        return LayoutProfile.DATE_INLINE;
    }

    private static boolean matchesSection(
            ResumeTextBlock block,
            List<ResumeTextBlock> headings,
            boolean projects) {
        ResumeTextBlock heading = precedingHeading(block, headings);
        if (heading == null) {
            return true;
        }
        String text = heading.getText() == null ? "" : heading.getText();
        boolean project = PROJECT_HEADING.matcher(text).find();
        boolean education = EDUCATION_HEADING.matcher(text).find();
        if (!project && !education) {
            return true;
        }
        return projects ? project : education;
    }

    private static ResumeTextBlock precedingHeading(ResumeTextBlock block, List<ResumeTextBlock> headings) {
        ResumeTextBlock best = null;
        for (ResumeTextBlock heading : headings) {
            if (heading.getPage() != block.getPage()) {
                continue;
            }
            if (heading.getY() > block.getY()) {
                continue;
            }
            if (best == null || heading.getY() > best.getY()) {
                best = heading;
            }
        }
        return best;
    }

    private static String voteDateAlignment(
            ResumeTextBlock block,
            List<ResumeTextBlock> blocks,
            float pageWidth) {
        if (block.getMaxInternalGap() >= Math.max(36f, pageWidth * 0.06f)) {
            return LayoutProfile.DATE_RIGHT;
        }
        String first = firstLine(block.getText());
        if (containsDate(first)
                && !looksLikeDate(first)
                && first.length() > 20
                && block.getX() + block.getWidth() >= pageWidth * 0.78f) {
            return LayoutProfile.DATE_RIGHT;
        }
        if (looksLikeDate(first)
                && first.length() <= 40
                && block.getX() + block.getWidth() >= pageWidth * 0.70f
                && block.getX() >= pageWidth * 0.45f) {
            return LayoutProfile.DATE_RIGHT;
        }
        ResumeTextBlock neighbor = sameLineNeighbor(block, blocks);
        if (neighbor != null) {
            float gap = block.getX() - (neighbor.getX() + neighbor.getWidth());
            if (gap >= Math.max(40f, pageWidth * 0.08f) && looksLikeDate(firstLine(block.getText()))) {
                return LayoutProfile.DATE_RIGHT;
            }
        }
        String[] lines = splitLines(block.getText());
        if (lines.length >= 2 && looksLikeDate(lines[lines.length - 1]) && !containsDate(lines[0])) {
            return LayoutProfile.DATE_LEFT;
        }
        return LayoutProfile.DATE_INLINE;
    }

    private static ResumeTextBlock sameLineNeighbor(ResumeTextBlock block, List<ResumeTextBlock> blocks) {
        ResumeTextBlock best = null;
        for (ResumeTextBlock other : blocks) {
            if (other == block || other.getPage() != block.getPage()) {
                continue;
            }
            if (Math.abs(other.getY() - block.getY()) > 4f) {
                continue;
            }
            if (other.getX() >= block.getX()) {
                continue;
            }
            if (best == null || other.getX() > best.getX()) {
                best = other;
            }
        }
        return best;
    }

    private static String skillPresentation(List<ResumeTextBlock> blocks) {
        int categoryLines = 0;
        for (ResumeTextBlock block : blocks) {
            if (block.getText() == null) {
                continue;
            }
            Matcher matcher = CATEGORY_LINE.matcher(block.getText());
            while (matcher.find()) {
                categoryLines++;
            }
        }
        return categoryLines >= 2 ? LayoutProfile.SKILL_GROUPED : LayoutProfile.SKILL_FLAT;
    }

    private static int columnCount(List<ResumeTextBlock> blocks, float pageWidth) {
        List<Float> lefts = new ArrayList<>();
        for (ResumeTextBlock block : blocks) {
            if (block.getPage() == 1 && block.getWidth() < pageWidth * 0.55f) {
                lefts.add(block.getX());
            }
        }
        lefts.sort(Float::compare);
        float largestGap = 0f;
        float splitAt = -1f;
        for (int i = 1; i < lefts.size(); i++) {
            float gap = lefts.get(i) - lefts.get(i - 1);
            if (gap > largestGap) {
                largestGap = gap;
                splitAt = (lefts.get(i) + lefts.get(i - 1)) / 2f;
            }
        }
        if (largestGap < pageWidth * 0.18f || splitAt <= 0f) {
            return 1;
        }
        int leftCount = 0;
        int rightCount = 0;
        for (ResumeTextBlock block : blocks) {
            if (block.getPage() != 1 || block.getWidth() >= pageWidth * 0.55f) {
                continue;
            }
            if (block.getX() < splitAt) {
                leftCount++;
            } else {
                rightCount++;
            }
        }
        if (leftCount >= 3 && rightCount >= 3) {
            return 2;
        }
        return 1;
    }

    private static String confidenceFor(
            LayoutProfile profile,
            List<ResumeTextBlock> blocks,
            List<ResumeTextBlock> headings) {
        if (blocks.size() < 3 || profile.getBaseFontSizePt() <= 0f) {
            return LayoutProfile.CONFIDENCE_LOW;
        }
        boolean pageKnown = !LayoutProfile.PAGE_UNKNOWN.equals(profile.getPageSize());
        boolean hasName = profile.getHeaderAlignment() != null;
        if (pageKnown && hasName && profile.getBaseFontSizePt() > 0f && headings.size() >= 2) {
            return LayoutProfile.CONFIDENCE_HIGH;
        }
        if (pageKnown && profile.getBaseFontSizePt() > 0f) {
            return LayoutProfile.CONFIDENCE_MEDIUM;
        }
        return LayoutProfile.CONFIDENCE_LOW;
    }

    private static List<ResumeTextBlock> pageBlocks(List<ResumeTextBlock> blocks, int page) {
        List<ResumeTextBlock> pageBlocks = new ArrayList<>();
        for (ResumeTextBlock block : blocks) {
            if (block.getPage() == page) {
                pageBlocks.add(block);
            }
        }
        return pageBlocks;
    }

    private static boolean hasFeature(ResumeTextBlock block, String feature) {
        return block.getFeatures() != null && block.getFeatures().contains(feature);
    }

    private static boolean containsDate(String text) {
        return text != null && DATE_RANGE.matcher(text).find();
    }

    private static boolean looksLikeDate(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return DATE_RANGE.matcher(trimmed).find() && trimmed.length() <= 48;
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }

    private static String[] splitLines(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        return text.split("\\R");
    }

    private static String maxKey(Map<String, Integer> counts, String fallback) {
        String best = fallback == null ? "" : fallback;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    private static float round1(float value) {
        return Math.round(value * 10f) / 10f;
    }

    private record FontStat(String family, float size) {
    }
}
