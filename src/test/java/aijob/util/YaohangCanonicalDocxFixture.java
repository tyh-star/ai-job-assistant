package aijob.util;

import aijob.dto.PdfLayout;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructure.ResumeProject;
import aijob.dto.ResumeStructureLlmEducation;
import aijob.dto.ResumeStructureLlmProject;
import aijob.dto.ResumeStructureLlmResponse;
import aijob.dto.ResumeStructureLlmText;
import aijob.dto.ResumeTextBlock;
import aijob.service.impl.ResumeTemplateDocxServiceImpl;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a canonical TEMPLATE DOCX in {@code @TempDir} from the real Yaohang PDF.
 * Classification copies grounded PDF block text only; no OpenAI call and no ACME internship.
 */
public final class YaohangCanonicalDocxFixture {

    private YaohangCanonicalDocxFixture() {
    }

    public static Path pdf() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                YaohangCanonicalDocxFixture.class.getClassLoader().getResource("resumes/yaohang-tang.pdf")
        ).toURI());
    }

    static ResumeStructure structureFromPdf(Path pdf) {
        PdfLayout layout = new PdfLayoutExtractor().extract(pdf);
        ResumeStructure draft = new ResumeStructureDraftBuilder().build(layout);
        ResumeTextBlock summaryBlock = findBlock(layout, "SDLC");
        ResumeTextBlock skillBlock = findBlock(layout, "Programming Languages");
        ResumeTextBlock prime = findBlock(layout, "Prime Deals");
        ResumeTextBlock smartPc = findBlock(layout, "SmartPC Builder");
        ResumeTextBlock york = findBlock(layout, "Bachelor of Computer Science");
        ResumeTextBlock courses = findBlock(layout, "Relevant Courses");

        ResumeStructureLlmResponse llm = new ResumeStructureLlmResponse();
        llm.summaryBullets = copiedSummaryBullets(summaryBlock);
        llm.skills = copiedSkills(skillBlock, "Java", "Python", "C/C++", "SQL", "HTML", "Vue", "Javascript", "CSS", "MySQL");
        llm.projects = List.of(
                project(
                        "Prime Deals",
                        copiedIfPresent(prime, "Personal Project"),
                        copiedBetween(prime, "Sept 2024", "Present"),
                        copiedBullets(prime),
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
        llm.experience = List.of();

        ResumeStructure merged = new ResumeStructureGrounder().merge(layout, draft, llm);
        ResumeProject primeProject = projectNamed(merged, "Prime Deals");
        ResumeProject smartProject = projectNamed(merged, "SmartPC Builder");
        if (primeProject.getBullets().isEmpty() || smartProject.getBullets().isEmpty()) {
            throw new IllegalStateException("Canonical structure missing project bullets from PDF");
        }
        return merged;
    }

    public static Path writeCanonicalDocx(Path pdf, Path outputDocx) {
        new ResumeTemplateDocxServiceImpl().write(structureFromPdf(pdf), outputDocx);
        return outputDocx;
    }

    private static ResumeTextBlock findBlock(PdfLayout layout, String needle) {
        return layout.getBlocks().stream()
                .filter(block -> block.getText() != null && block.getText().contains(needle))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing PDF block containing: " + needle));
    }

    private static ResumeProject projectNamed(ResumeStructure structure, String name) {
        return structure.getProjects().stream()
                .filter(project -> project.getName() != null && project.getName().contains(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing project: " + name));
    }

    private static ResumeStructureLlmText provenanced(
            String value,
            String blockId,
            int page,
            String sourceText) {
        ResumeStructureLlmText item = new ResumeStructureLlmText();
        item.value = value;
        item.sourceBlockIds = List.of(blockId);
        item.sourcePage = page;
        item.sourceText = sourceText;
        item.confidence = "HIGH";
        return item;
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

    private static List<ResumeStructureLlmText> copiedSkills(ResumeTextBlock block, String... candidates) {
        List<ResumeStructureLlmText> skills = new ArrayList<>();
        String haystack = ResumeStructureGrounder.normalize(block.getText());
        for (String candidate : candidates) {
            if (haystack.contains(ResumeStructureGrounder.normalize(candidate))) {
                skills.add(provenanced(candidate, block.getId(), block.getPage(), candidate));
            }
        }
        return skills;
    }

    private static List<ResumeStructureLlmText> copiedSummaryBullets(ResumeTextBlock block) {
        List<ResumeStructureLlmText> bullets = new ArrayList<>();
        for (String line : splitBulletLines(block.getText())) {
            bullets.add(provenanced(line, block.getId(), block.getPage(), line));
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
        return fromGlyphs.size() > lines.size() ? fromGlyphs : lines;
    }

    private static String stripBulletGlyph(String line) {
        return line.replaceFirst(
                "^[\\u200B\\u200C\\uFEFF\\u00A0\\u2022\\u25CF\\u25E6\\u2043\\uF0B7\\u25AA\\u25A0\\-*·•●]+\\s*",
                ""
        ).trim();
    }

    private static List<String> copiedBullets(ResumeTextBlock block) {
        List<String> bullets = new ArrayList<>();
        String[] raw = block.getText().split("\\R");
        for (int i = 0; i < raw.length; i++) {
            String trimmed = stripBulletGlyph(raw[i]);
            if (trimmed.isBlank() || i == 0) {
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
            throw new IllegalStateException("Missing '" + start + "' .. '" + endInclusive + "' in: " + haystack);
        }
        return haystack.substring(from, to + endInclusive.length());
    }

    private static String copiedDegree(ResumeTextBlock block) {
        String haystack = ResumeStructureGrounder.normalize(block.getText());
        if (haystack.contains("Bachelor of Computer Science")) {
            return "Bachelor of Computer Science";
        }
        return "";
    }
}
