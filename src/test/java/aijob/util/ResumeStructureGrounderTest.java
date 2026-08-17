package aijob.util;

import aijob.dto.PdfLayout;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructure.ResumeBullet;
import aijob.dto.ResumeStructure.ResumeEducation;
import aijob.dto.ResumeStructure.ResumeProject;
import aijob.dto.ResumeStructure.ResumeSkill;
import aijob.dto.ResumeStructureLlmEducation;
import aijob.dto.ResumeStructureLlmExperience;
import aijob.dto.ResumeStructureLlmProject;
import aijob.dto.ResumeStructureLlmResponse;
import aijob.dto.ResumeStructureLlmText;
import aijob.dto.ResumeTextBlock;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeStructureGrounderTest {

    private final ResumeStructureGrounder grounder = new ResumeStructureGrounder();
    private final ResumeStructureDraftBuilder draftBuilder = new ResumeStructureDraftBuilder();
    private final PdfLayoutExtractor extractor = new PdfLayoutExtractor();

    @Test
    void merge_copiesGroundedFieldsAndLeavesContactFromRules() {
        PdfLayout layout = sampleLayout();
        ResumeStructure draft = draftBuilder.build(layout);

        ResumeStructureLlmResponse llm = new ResumeStructureLlmResponse();
        llm.summaryBullets = List.of(
                provenanced("Java, Spring Boot", "b-p1-3", 1, "Java, Spring Boot", "HIGH")
        );
        llm.skills = List.of(
                provenanced("Java", "b-p1-3", 1, "Java", "HIGH"),
                provenanced("Spring Boot", "b-p1-3", 1, "Spring Boot", "HIGH")
        );
        llm.projects = List.of(project("Job Tracker", List.of("Parsed job descriptions"), "b-p1-4", 1));
        llm.education = List.of(education("York University", "B.S. Computer Science", "b-p1-5", 1));

        ResumeStructure merged = grounder.merge(layout, draft, llm);

        assertEquals("Jane Doe", merged.getContact().getFullName());
        assertEquals("jane.doe@example.com", merged.getContact().getEmail());
        assertEquals("", merged.getSummary());
        assertEquals(1, merged.getSummaryBullets().size());
        assertEquals("Java, Spring Boot", merged.getSummaryBullets().get(0).getText());
        assertEquals(ResumeTextBlock.PROVENANCE_LLM, merged.getSummaryBullets().get(0).getProvenance());
        assertEquals(List.of("b-p1-3"), merged.getSummaryBullets().get(0).getSourceBlockIds());
        assertEquals(Integer.valueOf(ResumeStructure.STRUCTURE_FORMAT_VERSION), merged.getStructureFormatVersion());
        assertTrue(hasSkill(merged, "Java"));
        assertTrue(hasSkill(merged, "Spring Boot"));
        assertEquals("Job Tracker", merged.getProjects().get(0).getName());
        assertEquals("York University", merged.getEducation().get(0).getSchool());
        assertTrue(Boolean.TRUE.equals(merged.getLlmClassified()));
        assertNotNull(draft.getLayoutProfile());
        assertNotNull(merged.getLayoutProfile());
        assertEquals(
                draft.getLayoutProfile().getHeaderAlignment(),
                merged.getLayoutProfile().getHeaderAlignment()
        );
        assertFalse(unassignedIds(merged).contains("b-p1-3"));
        assertFalse(unassignedIds(merged).contains("b-p1-4"));
        assertTrue(unassignedIds(merged).contains("b-p1-2"));
        assertEquals(ResumeTextBlock.CONFIDENCE_LOW, leftover(merged, "b-p1-2").getConfidence());
    }

    @Test
    void merge_stripsInventedSkillAndReturnsBlockToUnassigned() {
        PdfLayout layout = sampleLayout();
        ResumeStructure draft = draftBuilder.build(layout);

        ResumeStructureLlmResponse llm = new ResumeStructureLlmResponse();
        llm.skills = List.of(provenanced("Kubernetes", "b-p1-3", 1, "Kubernetes", "HIGH"));

        ResumeStructure merged = grounder.merge(layout, draft, llm);

        assertTrue(merged.getSkills().isEmpty());
        assertTrue(unassignedIds(merged).contains("b-p1-3"));
        assertEquals(ResumeTextBlock.CONFIDENCE_LOW, leftover(merged, "b-p1-3").getConfidence());
        assertFalse(unassignedText(merged).toLowerCase(Locale.ROOT).contains("kubernetes"));
    }

    @Test
    void merge_rejectsUnknownSourceBlockId() {
        PdfLayout layout = sampleLayout();
        ResumeStructure draft = draftBuilder.build(layout);

        ResumeStructureLlmResponse llm = new ResumeStructureLlmResponse();
        llm.experience = List.of(experience("Acme", "Intern", "b-missing", 1, "Acme Intern"));

        ResumeStructure merged = grounder.merge(layout, draft, llm);

        assertTrue(merged.getExperience().isEmpty());
        assertTrue(unassignedIds(merged).contains("b-p1-2"));
        assertTrue(unassignedIds(merged).contains("b-p1-3"));
    }

    @Test
    void merge_rejectsMismatchedSourceText() {
        PdfLayout layout = sampleLayout();
        ResumeStructure draft = draftBuilder.build(layout);

        ResumeStructureLlmResponse llm = new ResumeStructureLlmResponse();
        llm.experience = List.of(experience("Acme", "Internship", "b-p1-3", 1, "Internship at Google"));

        ResumeStructure merged = grounder.merge(layout, draft, llm);

        assertTrue(merged.getExperience().isEmpty());
        assertTrue(unassignedIds(merged).contains("b-p1-3"));
        assertEquals(ResumeTextBlock.CONFIDENCE_LOW, leftover(merged, "b-p1-3").getConfidence());
    }

    @Test
    void yaohangTangPdf_keepsCopiedFactsAndDropsInventions() throws Exception {
        PdfLayout layout = extractor.extract(fixture("resumes/yaohang-tang.pdf"));
        ResumeStructure draft = draftBuilder.build(layout);

        ResumeTextBlock summaryBlock = findBlock(layout, "SDLC");
        ResumeTextBlock skillBlock = findBlock(layout, "Programming Languages");
        ResumeTextBlock prime = findBlock(layout, "Prime Deals");
        ResumeTextBlock smartPc = findBlock(layout, "SmartPC Builder");
        ResumeTextBlock york = findBlock(layout, "Bachelor of Computer Science");
        ResumeTextBlock courses = findBlock(layout, "Relevant Courses");

        List<ResumeStructureLlmText> summaryBullets = copiedSummaryBullets(summaryBlock);
        ResumeStructureLlmResponse llm = new ResumeStructureLlmResponse();
        llm.summaryBullets = summaryBullets;
        List<ResumeStructureLlmText> skills = copiedSkills(skillBlock, "Java", "Python", "C/C++", "MySQL", "Spring", "AWS");
        skills.add(provenanced("Docker", skillBlock.getId(), skillBlock.getPage(), "Docker", "HIGH"));
        skills.add(provenanced("Kubernetes", skillBlock.getId(), skillBlock.getPage(), "Kubernetes", "HIGH"));
        llm.skills = skills;
        llm.projects = List.of(
                project(
                        "Prime Deals",
                        copiedIfPresent(prime, "Personal Project"),
                        copiedBetween(prime, "Sept 2024", "Present"),
                        withProjectLinkLines(prime, copiedBullets(prime)),
                        copiedUrls(prime),
                        prime.getId(),
                        prime.getPage()
                ),
                project(
                        "SmartPC Builder",
                        copiedIfPresent(smartPc, "York University"),
                        copiedBetween(smartPc, "Jan 2025", "Mar 2025"),
                        copiedBullets(smartPc),
                        copiedUrls(smartPc),
                        smartPc.getId(),
                        smartPc.getPage()
                )
        );
        llm.education = List.of(education(
                "York University",
                copiedDegree(york),
                copiedBetween(york, "Sept 2023", "Present"),
                ResumeStructureGrounder.normalize(courses.getText()),
                List.of(york.getId(), courses.getId()),
                york.getPage()
        ));
        llm.experience = List.of(experience(
                "Internship",
                "Software Engineer Intern",
                skillBlock.getId(),
                skillBlock.getPage(),
                "Internship at Google"
        ));

        ResumeStructure merged = grounder.merge(layout, draft, llm);

        assertTrue(merged.getContact().getFullName().toLowerCase(Locale.ROOT).contains("tang")
                || merged.getContact().getFullName().toLowerCase(Locale.ROOT).contains("yaohang"));
        assertTrue(merged.getContact().getEmail().contains("@"));
        assertTrue(merged.getSummary() == null || merged.getSummary().isBlank());
        assertEquals(7, merged.getSummaryBullets().size(), summaryBlock.getText());
        String joinedSummary = merged.getSummaryBullets().stream().map(ResumeBullet::getText).reduce((a, b) -> a + " " + b).orElse("");
        assertFalse(merged.getSummaryBullets().stream().anyMatch(bullet ->
                bullet.getText().equals(joinedSummary) && merged.getSummaryBullets().size() > 1));
        for (ResumeBullet bullet : merged.getSummaryBullets()) {
            assertFalse(bullet.getText().isBlank());
            assertEquals(ResumeTextBlock.PROVENANCE_LLM, bullet.getProvenance());
            assertFalse(bullet.getSourceBlockIds().isEmpty());
            assertTrue(bullet.getText().length() < ResumeStructureGrounder.normalize(summaryBlock.getText()).length());
        }
        assertTrue(hasSkill(merged, "Java"));
        assertFalse(hasSkill(merged, "Docker"));
        assertFalse(hasSkill(merged, "Kubernetes"));
        assertEquals(2, merged.getProjects().size());
        ResumeProject primeProject = projectNamed(merged, "Prime Deals");
        assertEquals("Personal Project", primeProject.getOrganizationOrType());
        assertTrue(primeProject.getDates().contains("Sept 2024"));
        assertTrue(primeProject.getDates().contains("Present"));
        assertTrue(primeProject.getLinks().stream().anyMatch(link -> link.contains("35.183.41.31")));
        assertFalse(primeProject.getBullets().stream().anyMatch(bullet ->
                bullet.toLowerCase(Locale.ROOT).contains("project link")
                        || bullet.contains("http://")
                        || bullet.contains("https://")));
        ResumeProject smartProject = projectNamed(merged, "SmartPC Builder");
        assertEquals("York University", smartProject.getOrganizationOrType());
        assertTrue(smartProject.getDates().contains("Jan 2025"));
        assertTrue(smartProject.getDates().contains("Mar 2025"));
        ResumeEducation education = merged.getEducation().get(0);
        assertEquals("York University", education.getSchool());
        assertEquals("Bachelor of Computer Science", education.getDegree());
        assertTrue(education.getDates().contains("Sept 2023"));
        assertTrue(education.getDates().contains("Present"));
        assertTrue(education.getDetails().contains("Relevant Courses"));
        assertTrue(education.getDetails().contains("Algorithm"));
        assertTrue(education.getSourceBlockIds().contains(york.getId()));
        assertTrue(education.getSourceBlockIds().contains(courses.getId()));
        assertEquals(ResumeTextBlock.PROVENANCE_LLM, education.getProvenance());
        assertTrue(merged.getExperience().isEmpty());
        String joined = (joinedSummary + "\n" + skillNames(merged) + "\n" + unassignedText(merged))
                .toLowerCase(Locale.ROOT);
        assertFalse(joined.contains("internship at google"));
        assertFalse(skillNames(merged).toLowerCase(Locale.ROOT).contains("docker"));
        assertFalse(skillNames(merged).toLowerCase(Locale.ROOT).contains("kubernetes"));
        assertEquals(Integer.valueOf(ResumeStructure.STRUCTURE_FORMAT_VERSION), merged.getStructureFormatVersion());
    }

    private PdfLayout sampleLayout() {
        return PdfLayout.ofBlocks(612, 792, List.of(
                block("b-p1-0", 1, 40, 22, "Jane Doe", List.of(ResumeTextBlock.FEATURE_LARGE_FONT)),
                block("b-p1-1", 1, 70, 11, "jane.doe@example.com", List.of(ResumeTextBlock.FEATURE_EMAIL)),
                block("b-p1-2", 1, 120, 13, "SUMMARY OF QUALIFICATIONS", List.of(ResumeTextBlock.FEATURE_ALL_CAPS)),
                block("b-p1-3", 1, 150, 11, "Java, Spring Boot", List.of(ResumeTextBlock.FEATURE_COMMA_LIST)),
                block("b-p1-4", 1, 200, 11, "Job Tracker\nParsed job descriptions", List.of(ResumeTextBlock.FEATURE_BULLET)),
                block("b-p1-5", 1, 260, 11, "York University\nB.S. Computer Science", List.of())
        ));
    }

    private ResumeTextBlock block(
            String id,
            int page,
            float y,
            float fontSize,
            String text,
            List<String> features) {
        ResumeTextBlock block = new ResumeTextBlock();
        block.setId(id);
        block.setPage(page);
        block.setX(72);
        block.setY(y);
        block.setWidth(200);
        block.setHeight(14);
        block.setFontSize(fontSize);
        block.setText(text);
        block.setFeatures(new ArrayList<>(features));
        block.setConfidence(ResumeTextBlock.CONFIDENCE_LOW);
        block.normalize();
        return block;
    }

    private static ResumeStructureLlmText provenanced(
            String value,
            String blockId,
            int page,
            String sourceText,
            String confidence) {
        ResumeStructureLlmText item = new ResumeStructureLlmText();
        item.value = value;
        item.sourceBlockIds = List.of(blockId);
        item.sourcePage = page;
        item.sourceText = sourceText;
        item.confidence = confidence;
        return item;
    }

    private static ResumeStructureLlmProject project(String name, List<String> bullets, String blockId, int page) {
        return project(name, "", "", bullets, List.of(), blockId, page);
    }

    private static ResumeStructureLlmProject project(
            String name,
            String organizationOrType,
            String dates,
            List<String> bullets,
            List<String> links,
            String blockId,
            int page) {
        ResumeStructureLlmProject project = new ResumeStructureLlmProject();
        project.name = name;
        project.organizationOrType = organizationOrType;
        project.dates = dates;
        project.bullets = bullets;
        project.links = links;
        project.sourceBlockIds = List.of(blockId);
        project.sourcePage = page;
        project.sourceText = name;
        project.confidence = "HIGH";
        return project;
    }

    private static ResumeStructureLlmEducation education(String school, String degree, String blockId, int page) {
        return education(school, degree, "", "", List.of(blockId), page);
    }

    private static ResumeStructureLlmEducation education(
            String school,
            String degree,
            String dates,
            String details,
            List<String> blockIds,
            int page) {
        ResumeStructureLlmEducation education = new ResumeStructureLlmEducation();
        education.school = school;
        education.degree = degree;
        education.dates = dates;
        education.details = details;
        education.sourceBlockIds = blockIds;
        education.sourcePage = page;
        education.sourceText = school;
        education.confidence = "HIGH";
        return education;
    }

    private static ResumeStructureLlmExperience experience(
            String company,
            String title,
            String blockId,
            int page,
            String sourceText) {
        ResumeStructureLlmExperience job = new ResumeStructureLlmExperience();
        job.company = company;
        job.title = title;
        job.sourceBlockIds = List.of(blockId);
        job.sourcePage = page;
        job.sourceText = sourceText;
        job.confidence = "HIGH";
        return job;
    }

    private static List<ResumeStructureLlmText> copiedSkills(ResumeTextBlock block, String... candidates) {
        List<ResumeStructureLlmText> skills = new ArrayList<>();
        String haystack = ResumeStructureGrounder.normalize(block.getText());
        for (String candidate : candidates) {
            if (haystack.contains(ResumeStructureGrounder.normalize(candidate))) {
                skills.add(provenanced(candidate, block.getId(), block.getPage(), candidate, "HIGH"));
            }
        }
        return skills;
    }

    private static List<ResumeStructureLlmText> copiedSummaryBullets(ResumeTextBlock block) {
        List<ResumeStructureLlmText> bullets = new ArrayList<>();
        for (String line : splitBulletLines(block.getText())) {
            bullets.add(provenanced(line, block.getId(), block.getPage(), line, "HIGH"));
        }
        return bullets;
    }

    private static List<String> splitBulletLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String line : text.split("\\R")) {
            String trimmed = stripBulletGlyph(line);
            if (!trimmed.isBlank()) {
                lines.add(trimmed);
            }
        }
        if (lines.size() >= 7) {
            return lines;
        }
        List<String> fromGlyphs = new ArrayList<>();
        for (String part : text.split("[\\u2022\\u25CF\\u25E6\\u2043·•●\\uF0B7]")) {
            String trimmed = ResumeStructureGrounder.normalize(part);
            if (!trimmed.isBlank()) {
                fromGlyphs.add(trimmed);
            }
        }
        if (fromGlyphs.size() > lines.size()) {
            lines = fromGlyphs;
        }
        if (lines.size() >= 7) {
            return lines;
        }
        List<String> sentences = new ArrayList<>();
        for (String line : lines) {
            for (String sentence : line.split("(?<=\\.)\\s+")) {
                String trimmed = sentence.trim();
                if (!trimmed.isBlank()) {
                    sentences.add(trimmed);
                }
            }
        }
        return sentences.size() > lines.size() ? sentences : lines;
    }

    private static String stripBulletGlyph(String line) {
        return line.replaceFirst("^[\\u200B\\u200C\\uFEFF\\u00A0\\u2022\\u25CF\\u25E6\\u2043\\uF0B7\\u25AA\\u25A0\\-*·•●]+\\s*", "").trim();
    }

    private static List<String> copiedBullets(ResumeTextBlock block) {
        List<String> bullets = new ArrayList<>();
        String[] raw = block.getText().split("\\R");
        for (int i = 0; i < raw.length; i++) {
            String trimmed = stripBulletGlyph(raw[i]);
            if (trimmed.isBlank()) {
                continue;
            }
            if (i == 0) {
                continue;
            }
            bullets.add(trimmed);
        }
        return bullets;
    }

    private static List<String> copiedUrls(ResumeTextBlock block) {
        List<String> urls = new ArrayList<>();
        String haystack = ResumeStructureGrounder.normalize(block.getText());
        int from = 0;
        while (from < haystack.length()) {
            int http = indexOfUrl(haystack, from);
            if (http < 0) {
                break;
            }
            int end = haystack.indexOf(' ', http);
            String url = end < 0 ? haystack.substring(http) : haystack.substring(http, end);
            url = url.replaceAll("[.,;)\\]]+$", "");
            urls.add(url);
            from = http + url.length();
        }
        return urls;
    }

    private static int indexOfUrl(String haystack, int from) {
        int http = haystack.indexOf("http://", from);
        int https = haystack.indexOf("https://", from);
        if (http < 0) {
            return https;
        }
        if (https < 0) {
            return http;
        }
        return Math.min(http, https);
    }

    private static List<String> withProjectLinkLines(ResumeTextBlock block, List<String> bullets) {
        List<String> combined = new ArrayList<>(bullets);
        String haystack = ResumeStructureGrounder.normalize(block.getText());
        int labeled = haystack.toLowerCase(Locale.ROOT).indexOf("project link");
        if (labeled >= 0) {
            int http = indexOfUrl(haystack, labeled);
            if (http >= 0) {
                int end = haystack.indexOf(' ', http);
                String excerpt = end < 0 ? haystack.substring(labeled) : haystack.substring(labeled, end);
                if (!combined.contains(excerpt)) {
                    combined.add(excerpt);
                }
            }
        }
        return combined;
    }

    private static String copiedIfPresent(ResumeTextBlock block, String needle) {
        String haystack = ResumeStructureGrounder.normalize(block.getText());
        String normalized = ResumeStructureGrounder.normalize(needle);
        return haystack.contains(normalized) ? normalized : "";
    }

    private static String copiedBetween(ResumeTextBlock block, String start, String endInclusive) {
        String haystack = ResumeStructureGrounder.normalize(block.getText());
        int from = haystack.indexOf(start);
        int to = haystack.indexOf(endInclusive, from);
        if (from < 0 || to < 0) {
            throw new AssertionError("Missing '" + start + "' .. '" + endInclusive + "' in: " + haystack);
        }
        return haystack.substring(from, to + endInclusive.length());
    }

    private static String copiedDegree(ResumeTextBlock block) {
        String haystack = ResumeStructureGrounder.normalize(block.getText());
        if (haystack.contains("Bachelor of Computer Science")) {
            return "Bachelor of Computer Science";
        }
        for (String line : block.getText().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).contains("bachelor")
                    || trimmed.toLowerCase(Locale.ROOT).contains("b.s")
                    || trimmed.toLowerCase(Locale.ROOT).contains("computer")) {
                return trimmed;
            }
        }
        return "";
    }

    private static ResumeProject projectNamed(ResumeStructure structure, String name) {
        return structure.getProjects().stream()
                .filter(project -> project.getName() != null && project.getName().contains(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing project: " + name));
    }

    private static ResumeTextBlock findBlock(PdfLayout layout, String needle) {
        return layout.getBlocks().stream()
                .filter(block -> block.getText() != null && block.getText().contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing block containing: " + needle));
    }

    private Path fixture(String classpath) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(getClass().getClassLoader().getResource(classpath)).toURI());
    }

    private static boolean hasSkill(ResumeStructure structure, String name) {
        return structure.getSkills().stream().anyMatch(skill -> name.equals(skill.getName()));
    }

    private static String skillNames(ResumeStructure structure) {
        return String.join(", ", structure.getSkills().stream().map(ResumeSkill::getName).toList());
    }

    private static List<String> unassignedIds(ResumeStructure structure) {
        return structure.getUnassigned().stream().map(ResumeTextBlock::getId).toList();
    }

    private static ResumeTextBlock leftover(ResumeStructure structure, String id) {
        return structure.getUnassigned().stream()
                .filter(block -> id.equals(block.getId()))
                .findFirst()
                .orElseThrow();
    }

    private static String unassignedText(ResumeStructure structure) {
        return structure.getUnassigned().stream()
                .map(ResumeTextBlock::getText)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
