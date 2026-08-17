package aijob.util;

import aijob.dto.PdfLayout;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructure.ResumeContact;
import aijob.dto.ResumeTextBlock;
import aijob.enums.ResumeExtractionStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ResumeStructureDraftBuilder {

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
    private static final Set<String> NAME_STOPWORDS = Set.of(
            "summary", "qualification", "qualifications", "experience", "education",
            "skill", "skills", "project", "projects", "technical", "proficiency",
            "proficiencies", "certification", "certifications", "internship",
            "objective", "profile", "contact", "competenc", "award", "language"
    );

    public ResumeStructure build(PdfLayout layout) {
        ResumeStructure structure = ResumeStructure.empty();
        if (layout == null
                || layout.getBlocks() == null
                || layout.getBlocks().isEmpty()
                || ResumeExtractionStatus.IMAGE_ONLY.name().equals(layout.getStatus())) {
            structure.setExtractionStatus(ResumeExtractionStatus.IMAGE_ONLY.name());
            structure.setLayoutProfile(LayoutProfileBuilder.from(layout));
            return structure;
        }

        structure.setExtractionStatus(ResumeExtractionStatus.LAYOUT.name());
        ResumeContact contact = structure.getContact();
        Map<String, String> provenance = structure.getFieldProvenance();
        List<ResumeTextBlock> unassigned = new ArrayList<>();

        float pageHeight = layout.getPageHeight() <= 0f ? 800f : layout.getPageHeight();
        ResumeTextBlock nameBlock = selectNameBlock(layout, pageHeight);
        if (nameBlock != null) {
            contact.setFullName(firstLine(nameBlock.getText()));
            structure.getFieldConfidence().put("contact.fullName", ResumeTextBlock.CONFIDENCE_MEDIUM);
            provenance.put("contact.fullName", ResumeTextBlock.PROVENANCE_RULE);
        } else {
            structure.getFieldConfidence().put("contact.fullName", ResumeTextBlock.CONFIDENCE_LOW);
        }

        for (ResumeTextBlock block : layout.getBlocks()) {
            block.normalize();
            boolean consumedAsName = nameBlock != null && nameBlock.getId().equals(block.getId());
            harvestContact(contact, provenance, structure.getFieldConfidence(), block, pageHeight);
            if (consumedAsName) {
                continue;
            }
            if (isContactOnly(block.getText())) {
                continue;
            }
            unassigned.add(copyForUnassigned(block));
        }

        structure.setUnassigned(unassigned);
        structure.setLayoutProfile(LayoutProfileBuilder.from(layout));
        structure.getFieldConfidence().put(
                "contact.location",
                ResumeTextBlock.CONFIDENCE_LOW
        );
        return structure;
    }

    private ResumeTextBlock selectNameBlock(PdfLayout layout, float pageHeight) {
        float topBand = pageHeight * 0.32f;
        ResumeTextBlock best = null;
        for (ResumeTextBlock block : layout.getBlocks()) {
            if (block.getPage() != 1) {
                continue;
            }
            if (block.getY() > topBand) {
                continue;
            }
            if (!looksLikeName(block.getText())) {
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

    private boolean looksLikeName(String text) {
        String line = firstLine(text);
        if (line.isBlank() || line.contains("@") || line.matches(".*\\d.*")) {
            return false;
        }
        if (containsNameStopword(line)) {
            return false;
        }
        String[] words = line.split("\\s+");
        return words.length >= 2 && words.length <= 5 && line.length() <= 60;
    }

    private boolean containsNameStopword(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String stopword : NAME_STOPWORDS) {
            if (lower.contains(stopword)) {
                return true;
            }
        }
        return false;
    }

    private void harvestContact(
            ResumeContact contact,
            Map<String, String> provenance,
            Map<String, String> confidence,
            ResumeTextBlock block,
            float pageHeight) {

        Matcher email = EMAIL.matcher(block.getText());
        if (email.find() && contact.getEmail().isEmpty()) {
            contact.setEmail(email.group());
            confidence.put("contact.email", ResumeTextBlock.CONFIDENCE_HIGH);
            provenance.put("contact.email", ResumeTextBlock.PROVENANCE_RULE);
        }

        Matcher phone = PHONE.matcher(block.getText());
        if (phone.find() && contact.getPhone().isEmpty()) {
            contact.setPhone(phone.group().trim());
            confidence.put("contact.phone", ResumeTextBlock.CONFIDENCE_HIGH);
            provenance.put("contact.phone", ResumeTextBlock.PROVENANCE_RULE);
        }

        Matcher url = URL.matcher(block.getText());
        boolean topBand = block.getPage() == 1 && block.getY() <= pageHeight * 0.32f;
        while (url.find()) {
            String link = url.group().replaceAll("[),.;]+$", "");
            boolean professional = link.toLowerCase(Locale.ROOT).contains("linkedin.com")
                    || link.toLowerCase(Locale.ROOT).contains("github.com");
            if (!topBand && !professional) {
                continue;
            }
            if (!contact.getLinks().contains(link)) {
                contact.getLinks().add(link);
            }
        }
        if (!contact.getLinks().isEmpty()) {
            confidence.put("contact.links", ResumeTextBlock.CONFIDENCE_HIGH);
            provenance.put("contact.links", ResumeTextBlock.PROVENANCE_RULE);
        }
    }

    private boolean isContactOnly(String text) {
        return leftoverAfterContact(text).isBlank();
    }

    private String leftoverAfterContact(String text) {
        String remainder = EMAIL.matcher(text).replaceAll(" ");
        remainder = PHONE.matcher(remainder).replaceAll(" ");
        remainder = URL.matcher(remainder).replaceAll(" ");
        remainder = remainder.replace("|", " ");
        return remainder.replaceAll("\\s+", " ").trim();
    }

    private String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }

    private ResumeTextBlock copyForUnassigned(ResumeTextBlock block) {
        ResumeTextBlock copy = new ResumeTextBlock();
        copy.setId(block.getId());
        copy.setPage(block.getPage());
        copy.setX(block.getX());
        copy.setY(block.getY());
        copy.setWidth(block.getWidth());
        copy.setHeight(block.getHeight());
        copy.setFontSize(block.getFontSize());
        copy.setFontFamily(block.getFontFamily());
        copy.setBold(block.isBold());
        copy.setUnderline(block.isUnderline());
        copy.setColumnIndex(block.getColumnIndex());
        copy.setMaxInternalGap(block.getMaxInternalGap());
        copy.setContentX(block.getContentX());
        copy.setText(block.getText());
        copy.setFeatures(new ArrayList<>(block.getFeatures()));
        copy.setConfidence(block.getConfidence());
        copy.normalize();
        return copy;
    }
}
