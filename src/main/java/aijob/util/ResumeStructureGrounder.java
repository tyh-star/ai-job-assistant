package aijob.util;

import aijob.dto.PdfLayout;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructure.ResumeBullet;
import aijob.dto.ResumeStructure.ResumeContact;
import aijob.dto.ResumeStructure.ResumeEducation;
import aijob.dto.ResumeStructure.ResumeExperience;
import aijob.dto.ResumeStructure.ResumeProject;
import aijob.dto.ResumeStructure.ResumeSkill;
import aijob.dto.ResumeStructureLlmEducation;
import aijob.dto.ResumeStructureLlmExperience;
import aijob.dto.ResumeStructureLlmProject;
import aijob.dto.ResumeStructureLlmResponse;
import aijob.dto.ResumeStructureLlmText;
import aijob.dto.ResumeTextBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ResumeStructureGrounder {

    private static final Pattern LEADING_BULLET_GLYPH = Pattern.compile(
            "^[\\u200B\\u200C\\uFEFF\\u00A0\\u2022\\u25CF\\u25E6\\u2043\\uF0B7\\u25AA\\u25A0\\-*·•●]+\\s*"
    );

    public ResumeStructure merge(
            PdfLayout layout,
            ResumeStructure ruleDraft,
            ResumeStructureLlmResponse llm) {

        ResumeStructure result = ResumeStructure.empty();
        if (ruleDraft != null) {
            result.setResumeId(ruleDraft.getResumeId());
            result.setReviewStatus(ruleDraft.getReviewStatus());
            result.setExtractionStatus(ruleDraft.getExtractionStatus());
            result.setContact(copyContact(ruleDraft.getContact()));
            result.setFieldConfidence(new LinkedHashMap<>(ruleDraft.getFieldConfidence()));
            result.setFieldProvenance(new LinkedHashMap<>(ruleDraft.getFieldProvenance()));
            result.setLayoutProfile(ruleDraft.getLayoutProfile());
        }
        result.setLlmClassified(Boolean.TRUE);
        result.setStructureFormatVersion(ResumeStructure.STRUCTURE_FORMAT_VERSION);
        result.normalize();

        Map<String, ResumeTextBlock> blocksById = index(layout);
        Set<String> consumed = new LinkedHashSet<>();

        if (llm != null) {
            applySummaryBullets(result, llm.summaryBullets, blocksById, consumed);
            applySkills(result, llm.skills, blocksById, consumed);
            applyExperience(result, llm.experience, blocksById, consumed);
            applyProjects(result, llm.projects, blocksById, consumed);
            applyEducation(result, llm.education, blocksById, consumed);
        }

        result.setUnassigned(leftoverUnassigned(ruleDraft, consumed));
        result.normalize();
        return result;
    }

    private void applySummaryBullets(
            ResumeStructure result,
            List<ResumeStructureLlmText> bullets,
            Map<String, ResumeTextBlock> blocksById,
            Set<String> consumed) {

        if (bullets == null) {
            return;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ResumeStructureLlmText bullet : bullets) {
            GroundedFields grounded = ground(bullet, blocksById);
            if (grounded == null) {
                continue;
            }
            String text = stripLeadingBulletGlyph(grounded.value, blocksById, grounded.sourceBlockIds);
            if (text.isBlank() || !seen.add(text)) {
                continue;
            }
            ResumeBullet item = new ResumeBullet();
            item.setText(text);
            item.setSourceBlockIds(new ArrayList<>(grounded.sourceBlockIds));
            item.setSourcePage(grounded.sourcePage);
            item.setSourceText(grounded.sourceText);
            item.setConfidence(grounded.confidence);
            item.setProvenance(ResumeTextBlock.PROVENANCE_LLM);
            result.getSummaryBullets().add(item);
            consumed.addAll(grounded.sourceBlockIds);
        }
        if (!result.getSummaryBullets().isEmpty()) {
            result.getFieldProvenance().put("summaryBullets", ResumeTextBlock.PROVENANCE_LLM);
            result.getFieldConfidence().put("summaryBullets", ResumeTextBlock.CONFIDENCE_MEDIUM);
        }
    }

    private void applySkills(
            ResumeStructure result,
            List<ResumeStructureLlmText> skills,
            Map<String, ResumeTextBlock> blocksById,
            Set<String> consumed) {

        if (skills == null) {
            return;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ResumeStructureLlmText skill : skills) {
            GroundedFields grounded = ground(skill, blocksById);
            if (grounded == null || grounded.value.isBlank() || !seen.add(grounded.value)) {
                continue;
            }
            ResumeSkill item = new ResumeSkill();
            item.setName(grounded.value);
            copyProvenance(item, grounded);
            result.getSkills().add(item);
            consumed.addAll(grounded.sourceBlockIds);
        }
        if (!result.getSkills().isEmpty()) {
            result.getFieldProvenance().put("skills", ResumeTextBlock.PROVENANCE_LLM);
            result.getFieldConfidence().put("skills", ResumeTextBlock.CONFIDENCE_MEDIUM);
        }
    }

    private void applyExperience(
            ResumeStructure result,
            List<ResumeStructureLlmExperience> jobs,
            Map<String, ResumeTextBlock> blocksById,
            Set<String> consumed) {

        if (jobs == null) {
            return;
        }
        for (ResumeStructureLlmExperience job : jobs) {
            if (job == null) {
                continue;
            }
            List<ResumeTextBlock> cited = resolveCited(job.sourceBlockIds, job.sourcePage, job.sourceText, blocksById);
            if (cited == null) {
                continue;
            }
            ResumeExperience item = new ResumeExperience();
            item.setCompany(groundedValue(job.company, cited));
            item.setTitle(groundedValue(job.title, cited));
            item.setStartDate(groundedValue(job.startDate, cited));
            item.setEndDate(groundedValue(job.endDate, cited));
            item.setLocation(groundedValue(job.location, cited));
            item.setBullets(groundedList(job.bullets, cited));
            if (item.getCompany().isBlank() && item.getTitle().isBlank() && item.getBullets().isEmpty()) {
                continue;
            }
            applyItemProvenance(
                    item::setSourceBlockIds,
                    item::setSourcePage,
                    item::setSourceText,
                    item::setConfidence,
                    item::setProvenance,
                    cited,
                    job.sourceText,
                    job.confidence
            );
            result.getExperience().add(item);
            consumed.addAll(idsOf(cited));
        }
        if (!result.getExperience().isEmpty()) {
            result.getFieldProvenance().put("experience", ResumeTextBlock.PROVENANCE_LLM);
        }
    }

    private void applyProjects(
            ResumeStructure result,
            List<ResumeStructureLlmProject> projects,
            Map<String, ResumeTextBlock> blocksById,
            Set<String> consumed) {

        if (projects == null) {
            return;
        }
        for (ResumeStructureLlmProject project : projects) {
            if (project == null) {
                continue;
            }
            List<ResumeTextBlock> cited = resolveCited(
                    project.sourceBlockIds, project.sourcePage, project.sourceText, blocksById);
            if (cited == null) {
                continue;
            }
            ResumeProject item = new ResumeProject();
            item.setName(groundedValue(project.name, cited));
            item.setOrganizationOrType(groundedValue(project.organizationOrType, cited));
            item.setDates(groundedValue(project.dates, cited));
            item.setBullets(groundedList(project.bullets, cited));
            item.setLinks(groundedList(project.links, cited));
            moveProjectLinksOutOfBullets(item, cited);
            if (item.getName().isBlank()) {
                continue;
            }
            applyItemProvenance(
                    item::setSourceBlockIds,
                    item::setSourcePage,
                    item::setSourceText,
                    item::setConfidence,
                    item::setProvenance,
                    cited,
                    project.sourceText,
                    project.confidence
            );
            result.getProjects().add(item);
            consumed.addAll(idsOf(cited));
        }
        if (!result.getProjects().isEmpty()) {
            result.getFieldProvenance().put("projects", ResumeTextBlock.PROVENANCE_LLM);
        }
    }

    private void applyEducation(
            ResumeStructure result,
            List<ResumeStructureLlmEducation> schools,
            Map<String, ResumeTextBlock> blocksById,
            Set<String> consumed) {

        if (schools == null) {
            return;
        }
        for (ResumeStructureLlmEducation school : schools) {
            if (school == null) {
                continue;
            }
            List<ResumeTextBlock> cited = resolveCited(
                    school.sourceBlockIds, school.sourcePage, school.sourceText, blocksById);
            if (cited == null) {
                continue;
            }
            ResumeEducation item = new ResumeEducation();
            item.setSchool(groundedValue(school.school, cited));
            item.setDegree(groundedValue(school.degree, cited));
            item.setDates(groundedValue(school.dates, cited));
            item.setDetails(groundedValue(school.details, cited));
            if (item.getSchool().isBlank() && item.getDegree().isBlank()) {
                continue;
            }
            applyItemProvenance(
                    item::setSourceBlockIds,
                    item::setSourcePage,
                    item::setSourceText,
                    item::setConfidence,
                    item::setProvenance,
                    cited,
                    school.sourceText,
                    school.confidence
            );
            result.getEducation().add(item);
            consumed.addAll(idsOf(cited));
        }
        if (!result.getEducation().isEmpty()) {
            result.getFieldProvenance().put("education", ResumeTextBlock.PROVENANCE_LLM);
        }
    }

    private GroundedFields ground(
            ResumeStructureLlmText value,
            Map<String, ResumeTextBlock> blocksById) {

        if (value == null || value.value == null || value.value.isBlank()) {
            return null;
        }
        List<ResumeTextBlock> cited = resolveCited(
                value.sourceBlockIds, value.sourcePage, value.sourceText, blocksById);
        if (cited == null) {
            return null;
        }
        String copied = groundedValue(value.value, cited);
        if (copied.isBlank()) {
            return null;
        }
        String sourceText = groundedValue(
                value.sourceText == null || value.sourceText.isBlank() ? copied : value.sourceText,
                cited
        );
        if (sourceText.isBlank()) {
            return null;
        }
        return new GroundedFields(
                copied,
                idsOf(cited),
                cited.get(0).getPage(),
                sourceText,
                normalizeConfidence(value.confidence)
        );
    }

    private List<ResumeTextBlock> resolveCited(
            List<String> sourceBlockIds,
            Integer sourcePage,
            String sourceText,
            Map<String, ResumeTextBlock> blocksById) {

        if (sourceBlockIds == null || sourceBlockIds.isEmpty()) {
            return null;
        }
        List<ResumeTextBlock> cited = new ArrayList<>();
        for (String id : sourceBlockIds) {
            if (id == null || id.isBlank()) {
                return null;
            }
            ResumeTextBlock block = blocksById.get(id);
            if (block == null) {
                return null;
            }
            cited.add(block);
        }
        if (sourcePage == null) {
            return null;
        }
        for (ResumeTextBlock block : cited) {
            if (block.getPage() != sourcePage) {
                return null;
            }
        }
        if (sourceText != null && !sourceText.isBlank() && groundedValue(sourceText, cited).isBlank()) {
            return null;
        }
        return cited;
    }

    private String groundedValue(String value, List<ResumeTextBlock> cited) {
        if (value == null) {
            return "";
        }
        String needle = normalize(value);
        if (needle.isEmpty()) {
            return "";
        }
        String haystack = haystack(cited);
        if (!haystack.contains(needle)) {
            return "";
        }
        return needle;
    }

    private void moveProjectLinksOutOfBullets(ResumeProject item, List<ResumeTextBlock> cited) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        for (String link : item.getLinks()) {
            String url = projectLinkUrl(link);
            String grounded = groundedValue(url == null ? link : url, cited);
            if (!grounded.isBlank()) {
                String extracted = projectLinkUrl(grounded);
                links.add(extracted == null ? grounded : extracted);
            }
        }
        List<String> remaining = new ArrayList<>();
        for (String bullet : item.getBullets()) {
            String url = projectLinkUrl(bullet);
            if (url != null) {
                String grounded = groundedValue(url, cited);
                if (!grounded.isBlank()) {
                    String extracted = projectLinkUrl(grounded);
                    links.add(extracted == null ? grounded : extracted);
                    continue;
                }
            }
            remaining.add(bullet);
        }
        item.setBullets(remaining);
        item.setLinks(new ArrayList<>(links));
    }

    private String projectLinkUrl(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        Matcher labeled = Pattern.compile("(?i)project\\s*link\\s*:\\s*(https?://\\S+)").matcher(normalized);
        if (labeled.find()) {
            return labeled.group(1).replaceAll("[.,;)\\]]+$", "");
        }
        if (normalized.matches("(?i)https?://\\S+")) {
            return normalized.replaceAll("[.,;)\\]]+$", "");
        }
        return null;
    }

    private List<String> groundedList(List<String> values, List<ResumeTextBlock> cited) {
        List<String> grounded = new ArrayList<>();
        if (values == null) {
            return grounded;
        }
        for (String value : values) {
            String copied = stripLeadingBulletGlyph(groundedValue(value, cited), cited);
            if (!copied.isBlank()) {
                grounded.add(copied);
            }
        }
        return grounded;
    }

    private List<ResumeTextBlock> leftoverUnassigned(ResumeStructure ruleDraft, Set<String> consumed) {
        List<ResumeTextBlock> leftover = new ArrayList<>();
        if (ruleDraft == null || ruleDraft.getUnassigned() == null) {
            return leftover;
        }
        for (ResumeTextBlock block : ruleDraft.getUnassigned()) {
            if (block.getId() != null && consumed.contains(block.getId())) {
                continue;
            }
            leftover.add(copyUnassigned(block));
        }
        return leftover;
    }

    private String stripLeadingBulletGlyph(
            String value,
            Map<String, ResumeTextBlock> blocksById,
            List<String> sourceBlockIds) {

        List<ResumeTextBlock> cited = new ArrayList<>();
        if (sourceBlockIds != null) {
            for (String id : sourceBlockIds) {
                ResumeTextBlock block = blocksById.get(id);
                if (block != null) {
                    cited.add(block);
                }
            }
        }
        return stripLeadingBulletGlyph(value, cited);
    }

    private String stripLeadingBulletGlyph(String value, List<ResumeTextBlock> cited) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String stripped = LEADING_BULLET_GLYPH.matcher(value).replaceFirst("").trim();
        if (stripped.isBlank()) {
            return value;
        }
        String grounded = groundedValue(stripped, cited);
        return grounded.isBlank() ? value : grounded;
    }

    private ResumeContact copyContact(ResumeContact source) {
        ResumeContact copy = new ResumeContact();
        if (source == null) {
            copy.normalize();
            return copy;
        }
        copy.setFullName(source.getFullName());
        copy.setEmail(source.getEmail());
        copy.setPhone(source.getPhone());
        copy.setLocation(source.getLocation());
        copy.setLinks(source.getLinks() == null ? new ArrayList<>() : new ArrayList<>(source.getLinks()));
        copy.normalize();
        return copy;
    }

    private ResumeTextBlock copyUnassigned(ResumeTextBlock block) {
        ResumeTextBlock copy = new ResumeTextBlock();
        copy.setId(block.getId());
        copy.setPage(block.getPage());
        copy.setX(block.getX());
        copy.setY(block.getY());
        copy.setWidth(block.getWidth());
        copy.setHeight(block.getHeight());
        copy.setFontSize(block.getFontSize());
        copy.setBold(block.isBold());
        copy.setColumnIndex(block.getColumnIndex());
        copy.setText(block.getText());
        copy.setFeatures(block.getFeatures() == null ? new ArrayList<>() : new ArrayList<>(block.getFeatures()));
        copy.setConfidence(ResumeTextBlock.CONFIDENCE_LOW);
        copy.setProvenance(block.getProvenance());
        copy.normalize();
        return copy;
    }

    private Map<String, ResumeTextBlock> index(PdfLayout layout) {
        Map<String, ResumeTextBlock> blocksById = new LinkedHashMap<>();
        if (layout == null || layout.getBlocks() == null) {
            return blocksById;
        }
        for (ResumeTextBlock block : layout.getBlocks()) {
            if (block.getId() != null) {
                blocksById.put(block.getId(), block);
            }
        }
        return blocksById;
    }

    private String haystack(List<ResumeTextBlock> cited) {
        StringBuilder builder = new StringBuilder();
        for (ResumeTextBlock block : cited) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(normalize(block.getText()));
        }
        return builder.toString();
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private String normalizeConfidence(String confidence) {
        if (ResumeTextBlock.CONFIDENCE_HIGH.equalsIgnoreCase(confidence)
                || ResumeTextBlock.CONFIDENCE_MEDIUM.equalsIgnoreCase(confidence)
                || ResumeTextBlock.CONFIDENCE_LOW.equalsIgnoreCase(confidence)) {
            return confidence.toUpperCase();
        }
        return ResumeTextBlock.CONFIDENCE_MEDIUM;
    }

    private List<String> idsOf(List<ResumeTextBlock> cited) {
        List<String> ids = new ArrayList<>();
        for (ResumeTextBlock block : cited) {
            ids.add(block.getId());
        }
        return ids;
    }

    private void copyProvenance(ResumeSkill item, GroundedFields grounded) {
        item.setSourceBlockIds(new ArrayList<>(grounded.sourceBlockIds));
        item.setSourcePage(grounded.sourcePage);
        item.setSourceText(grounded.sourceText);
        item.setConfidence(grounded.confidence);
        item.setProvenance(ResumeTextBlock.PROVENANCE_LLM);
    }

    private void applyItemProvenance(
            java.util.function.Consumer<List<String>> setIds,
            java.util.function.Consumer<Integer> setPage,
            java.util.function.Consumer<String> setSourceText,
            java.util.function.Consumer<String> setConfidence,
            java.util.function.Consumer<String> setProvenance,
            List<ResumeTextBlock> cited,
            String sourceText,
            String confidence) {
        setIds.accept(idsOf(cited));
        setPage.accept(cited.get(0).getPage());
        String excerpt = groundedValue(
                sourceText == null || sourceText.isBlank() ? cited.get(0).getText() : sourceText,
                cited
        );
        setSourceText.accept(excerpt.isBlank() ? normalize(cited.get(0).getText()) : excerpt);
        setConfidence.accept(normalizeConfidence(confidence));
        setProvenance.accept(ResumeTextBlock.PROVENANCE_LLM);
    }

    private record GroundedFields(
            String value,
            List<String> sourceBlockIds,
            int sourcePage,
            String sourceText,
            String confidence) {
    }
}
