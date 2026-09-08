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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministically PDFBox-generated, entirely fictional two-project single-page
 * resume PDF (Alex Morgan / Example University / Example Company) -- built fresh at
 * test time, never a checked-in binary and never a real person's data. Preserves the
 * exact same structural shape (a 7-line summary block containing "SDLC", a grouped
 * skills block, two distinct project blocks each with a heading+date line followed by
 * bullets, one bullet in the first project carrying a "Project Link:" URL line, and an
 * education block with school+degree+dates plus a "Relevant Courses" line) that the
 * grounding and tailor-pipeline tests were originally written against a real PDF for --
 * see ResumeStructureGrounderTest's generatedResumePdf_... test and
 * TailorV2T2B2GeneratedPdfTest for the exact facts this shape must produce.
 * <p>
 * "Prime Deals" and "SmartPC Builder" are synthetic fixture project names (canonical
 * keys {@code PROJECT:prime-deals} / {@code PROJECT:smartpc-builder}), not real
 * products or employers. Keep those labels so V3 entry keys stay stable.
 * <p>
 * Also builds the canonical TEMPLATE DOCX from that PDF, the same way
 * ResumeServiceImpl#canonicalizeResume does in production: extract layout, run a
 * (here, hand-authored rather than OpenAI-authored) classification response through
 * {@link ResumeStructureGrounder}, then render with {@link ResumeTemplateDocxServiceImpl}.
 * No OpenAI call anywhere in this class.
 */
public final class GeneratedResumePdfFixture {

    private static final Pattern ID_PATTERN =
            Pattern.compile("/ID\\s*\\[\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*\\]");

    private GeneratedResumePdfFixture() {
    }

    /** Writes the fictional resume to a fresh temp file and returns its path. The file
     * is marked delete-on-exit; callers needing tighter cleanup can write to their own
     * {@code @TempDir} via {@link #writePdf(Path)} instead. */
    public static Path pdf() throws IOException {
        Path tempPdf = Files.createTempFile("generated-resume-fixture", ".pdf");
        tempPdf.toFile().deleteOnExit();
        writePdf(tempPdf);
        return tempPdf;
    }

    public static void writePdf(Path outputPdf) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            // Times, not Helvetica: LayoutProfileBuilderTest's generated-resume-fixture
            // test expects the same Times/Roman body-font family that the original
            // real PDF this fixture replaces used (mirroring
            // PdfLayoutTestDocuments.writeLeftTimesRuleResume's font choice).
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
            PDType1Font plain = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                // Within a logical block, consecutive lines must stay close enough in Y
                // that PdfLayoutExtractor's own clustering (see its shouldSplit: a gap
                // over roughly 1.35x glyph height starts a new block) merges them into
                // ONE ResumeTextBlock with embedded newlines -- a 12pt step at 11pt font
                // does; the ~16-18pt steps used elsewhere in this test suite's OTHER
                // synthetic fixtures do not, which is why this fixture uses a tighter,
                // deliberately verified step within each block (see the companion
                // ZZDumpDiagnosticTest-style check run while authoring this). Gaps
                // between DIFFERENT blocks stay large (24-48pt) to guarantee a split.
                show(cs, bold, 20, 72, 740, "Alex Morgan");
                show(cs, plain, 11, 72, 720, "alex.morgan@example.com  +1 (555) 234-5678");
                show(cs, plain, 11, 72, 704, "https://linkedin.com/in/alexmorgan  https://github.com/alexmorgan");
                rule(cs, 692);

                show(cs, bold, 13, 72, 654, "SUMMARY OF QUALIFICATIONS");
                rule(cs, 650);
                show(cs, plain, 11, 72, 632, "Full-stack software engineer with experience across the SDLC from design to deployment.");
                show(cs, plain, 11, 72, 620, "Skilled in Java, Python, and modern web frameworks.");
                show(cs, plain, 11, 72, 608, "Experienced building and deploying cloud-hosted applications on AWS.");
                show(cs, plain, 11, 72, 596, "Strong background in object-oriented design and algorithms.");
                show(cs, plain, 11, 72, 584, "Effective communicator and collaborator in cross-functional teams.");
                show(cs, plain, 11, 72, 572, "Quick learner with a track record of delivering projects on schedule.");
                show(cs, plain, 11, 72, 560, "Passionate about clean code, testing, and continuous improvement.");

                show(cs, bold, 13, 72, 512, "TECHNICAL SKILLS");
                rule(cs, 508);
                // "Javascript" (lowercase "s") matches the exact casing of the
                // candidate list below -- ResumeStructureGrounder.normalize() only
                // collapses whitespace, it does not lowercase, so copiedSkills' plain
                // haystack.contains(candidate) check is case-sensitive.
                show(cs, plain, 11, 72, 490, "Programming Languages: Java, Python, C/C++, Javascript");
                show(cs, plain, 11, 72, 480, "Web Technologies: HTML, CSS, Vue, SQL, MySQL");
                // A tighter gap than the other within-block steps in this fixture --
                // empirically, per-glyph height (and so the real extractor's merge
                // threshold) varies slightly line to line with the substituted Times
                // fallback font, so this step is kept well under the threshold rather
                // than at the same nominal gap used elsewhere.
                show(cs, plain, 11, 72, 474, "Frameworks and Cloud: Spring Boot, AWS");

                show(cs, bold, 13, 72, 418, "RELEVANT PROJECTS");
                rule(cs, 414);
                // The date is drawn as a SECOND showText call, offset from the prefix's
                // measured end by ~50pt, rather than appended as plain text or placed at
                // a fixed far-right x. That gap is:
                //  - big enough (>36.72pt = max(36, pageWidth*0.06)) to register as this
                //    block's maxInternalGap, which is what LayoutProfileBuilder reads to
                //    classify a project heading's date as DATE_RIGHT-aligned; but
                //  - small enough (<73.44pt = max(24, pageWidth*0.12)) that
                //    PdfLayoutExtractor's own line-continuation check still folds it into
                //    the SAME Line as the prefix (rather than a fixed x like 420, which
                //    -- verified empirically with a throwaway diagnostic dump while
                //    authoring this fixture -- creates a second, distinct line-start x
                //    position on the page and wrongly trips PdfLayoutExtractor's GLOBAL
                //    two-column page detection, scrambling the reading order of every
                //    other short line on the page).
                String primeHeadingPrefix = "Prime Deals - Personal Project";
                float primeHeadingX = 72f;
                show(cs, bold, 11, primeHeadingX, 396, primeHeadingPrefix);
                float primeDateX = primeHeadingX + textWidth(bold, 11, primeHeadingPrefix) + 50f;
                show(cs, bold, 11, primeDateX, 396, "Sept 2024 - Present");
                // Bullet order matters here: several V3 vertical-slice tests script a
                // FIXED, position-mapped rewrite for a 5-writable-bullet Prime Deals
                // entry in exactly this order (lead/collaborate, UML/planning,
                // design/implement, REST integration, AWS deploy) -- see
                // V3PdfDocxVerticalTest's and V3OfflineQualityFixtureTest's
                // "writable.size() == 5" branches.
                //
                // Present tense + a recognized action verb ("collaborate"/"develop") so
                // this both (a) stays part of the Prime Deals ClaimLedger entry instead
                // of splitting into a new one, and (b) lets
                // ConservativeCandidateHardGate's citedSameEntryLeadership resolve a
                // "led ..." rewrite of the Spring Boot bullet when this node is cited.
                show(cs, plain, 11, 72, 384, "Lead and collaborate with a friend to develop a shopping website");
                // A second, independent Prime Deals bullet so gate tests can cite it as
                // same-entry supporting evidence distinct from the REST/AWS bullets.
                // Deliberately avoids the word "architecture" -- many other tests
                // freely rephrase the Spring Boot bullet's "MVC pattern" as "MVC
                // architecture", and since "architecture" is not one of
                // ConservativeMaterialFacts' GENERIC_TOKENS, having it originate from
                // this UNRELATED bullet was empirically confirmed (via a real JUnit
                // run while authoring this fixture) to make those Spring Boot rewrites
                // spuriously fail as SOURCE_FACT_WOULD_DUPLICATE. Wording (scope/
                // requirements/class/diagrams) is also chosen to line up with
                // V3PdfDocxVerticalTest's/V3OfflineQualityFixtureTest's scripted 5-line
                // Prime Deals rewrite ("...used UML class diagrams for planning"), so
                // this bullet's material facts stay fully retained by that rewrite
                // instead of tripping a MISSING_MATERIAL_FACT repair cycle.
                show(cs, plain, 11, 72, 372, "Designed UML Class Diagrams to plan project scope and requirements");
                show(cs, plain, 11, 72, 360, "Design and implement a Java Spring Boot MVC web application");
                // Authentication, product updates AND the shopping cart are kept in
                // this ONE bullet (rather than split across a separate "built a
                // shopping cart" bullet) because several downstream gate tests rewrite
                // this exact bullet with wording like "...product updates, and shopping
                // cart operations/management" and require that NOT to be flagged as
                // copying an external bullet's fact -- it only works if those facts are
                // already this bullet's own. The plural "managements" is a deliberate
                // grammar mistake in the original (matching
                // OpportunityResolutionGateTest's P17_STILL_MANAGEMENTS fixture, which
                // is that grammar opportunity left UNFIXED) -- OpportunityDetector's
                // GRAMMAR check needs a real defect in the original to fix.
                show(cs, plain, 11, 72, 348, "Integrated RESTful APIs to handle user authentication, product updates, and shopping cart managements");
                // "...the initial version of the project onto AWS" (not the shorter
                // "the application on AWS") matches the wording convention this whole
                // test suite already uses for an AWS-deploy bullet elsewhere (see e.g.
                // V3BasicGoldenTest/V3CanonicalEntryKeyTest/ConservativeMaterialFactsTest).
                // It also matters structurally: ResumeTailorNodeGuard.isBulletLike only
                // allows a bullet starting with a non-action word (like "Successfully")
                // through if its text is >=50 chars, and V3 vertical-slice tests that
                // script fixed rewrites for a "5 writable Prime Deals bullets" case
                // require this bullet to actually be writable.
                show(cs, plain, 11, 72, 336, "Successfully deployed the initial version of the project onto AWS");
                // Positioned close enough after the last bullet to merge into the same
                // block (rather than splitting off as its own block) so that
                // copiedUrls(prime), which only scans the "Prime Deals" block's own
                // text, can find this URL. (See the Frameworks/Cloud comment above on
                // why this step is tighter than other same-block gaps in this fixture.)
                show(cs, plain, 11, 72, 330, "Project Link: https://atlas.example.com");

                show(cs, bold, 11, 72, 288, "SmartPC Builder - Example University - Jan 2025 - Mar 2025");
                // "Developed..." carries no OOP/CPU facts of its own -- those live in
                // the two bullets below as SEPARATE, independently-citable facts, which
                // several ConservativeCandidateHardGate tests rely on (rewriting this
                // bullet must not be able to freely restate OOP/CPU without citing the
                // bullet that actually owns that fact).
                show(cs, plain, 11, 72, 276, "Developed a Java Maven project that helps users select optimal components within a given budget");
                show(cs, plain, 11, 72, 264, "Designed the project architecture using Object-Oriented Programming (OOP) principles for modular, maintainable code");
                // This exact phrasing ("that dynamically suggests components (CPU,
                // GPU, and RAM)") matches the wording convention used for this same
                // fictional bullet throughout the rest of the test suite (e.g.
                // ResumeRequirementMatcherTest.alexMorganNodes()'s p-20), and
                // SurfaceRewriteEquivalence.canonical() has literal normalization rules
                // built around exactly this "that dynamically suggests ... (CPU, GPU,
                // and RAM)" shape -- TailorV2T2D2A2CoherenceTest's p23SurfaceRewriteIsLowValue
                // rewrites this bullet to a synonym-only "to dynamically suggest
                // components such as CPU, GPU, and RAM" form and needs the two to
                // canonicalize identically. "components" (not "configurations") also
                // matches the word the Maven bullet above uses -- several gate tests
                // rewrite BOTH bullets with that word, and each stays self-covered
                // (not flagged as copying the OTHER bullet's fact) only because its own
                // original text already contains it too.
                show(cs, plain, 11, 72, 252, "Implemented an algorithm that dynamically suggests components (CPU, GPU, and RAM)");
                show(cs, plain, 11, 72, 240, "Delivered clean, well-documented code with Java Doc comments throughout");
                // "Collaborated" (a recognized ActionConceptRegistry action verb) is
                // needed so ClaimLedger classifies this as a continuing project bullet
                // rather than a new entry header -- "communicated" alone is not in that
                // registry's action-word list, which was empirically confirmed (via a
                // throwaway diagnostic run while authoring this fixture) to otherwise
                // split this line into its own separate ClaimLedger entry.
                show(cs, plain, 11, 72, 228, "Collaborated with the team and communicated effectively with the TA and professor throughout the project");

                show(cs, bold, 13, 72, 180, "EDUCATION");
                rule(cs, 176);
                // Kept as ONE line (rather than relying on multi-line clustering, which
                // proved unreliable for this specific pair during authoring) so the
                // block findBlock(layout, "Bachelor of Computer Science") returns always
                // contains the degree AND the "Sept 2023" .. "Present" date range
                // together -- see structureFromPdf's copiedDegree/copiedBetween calls.
                show(cs, bold, 11, 72, 158, "Example University - Bachelor of Computer Science - Sept 2023 - Present");
                show(cs, plain, 11, 72, 134, "Relevant Courses: Algorithms and Data Structures, Database Systems, Operating Systems");
            }
            document.save(outputPdf.toFile());
        }
        // PDFBox writes a fresh, effectively-random /ID pair (derived in part from the
        // current time) into the trailer on every save, so two separately-generated
        // copies of this otherwise byte-identical fixture would NOT be byte-identical
        // without this step -- V3EnhancedPdfConfirmVerticalTest explicitly asserts that
        // two independent GeneratedResumePdfFixture.pdf() calls hash the same.
        normalizeDocumentId(outputPdf);
    }

    private static void normalizeDocumentId(Path pdf) throws IOException {
        byte[] bytes = Files.readAllBytes(pdf);
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        Matcher matcher = ID_PATTERN.matcher(content);
        StringBuilder rewritten = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            rewritten.append(content, last, matcher.start());
            rewritten.append("/ID [<").append("0".repeat(matcher.group(1).length()))
                    .append("> <").append("0".repeat(matcher.group(2).length())).append(">]");
            last = matcher.end();
        }
        rewritten.append(content, last, content.length());
        Files.write(pdf, rewritten.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void rule(PDPageContentStream cs, float y) throws IOException {
        cs.setStrokingColor(0f, 0f, 0f);
        cs.setLineWidth(0.8f);
        cs.moveTo(72, y);
        cs.lineTo(540, y);
        cs.stroke();
    }

    private static float textWidth(PDType1Font font, int size, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    private static void show(
            PDPageContentStream cs,
            PDType1Font font,
            int size,
            float x,
            float y,
            String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    static ResumeStructure structureFromPdf(Path pdf) {
        PdfLayout layout = new PdfLayoutExtractor().extract(pdf);
        ResumeStructure draft = new ResumeStructureDraftBuilder().build(layout);
        ResumeTextBlock summaryBlock = findBlock(layout, "SDLC");
        ResumeTextBlock skillBlock = findBlock(layout, "Programming Languages");
        ResumeTextBlock prime = findBlock(layout, "Prime Deals");
        ResumeTextBlock smartPc = findBlock(layout, "SmartPC Builder");
        ResumeTextBlock school = findBlock(layout, "Bachelor of Computer Science");
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
                        copiedIfPresent(smartPc, "Example University"),
                        copiedBetween(smartPc, "Jan 2025", "Mar 2025"),
                        copiedBullets(smartPc),
                        copiedUrls(smartPc),
                        smartPc.getId(),
                        smartPc.getPage()
                )
        );
        llm.education = List.of(education(
                "Example University",
                copiedDegree(school),
                copiedBetween(school, "Sept 2023", "Present"),
                ResumeStructureGrounder.normalize(courses.getText()),
                List.of(school.getId(), courses.getId()),
                school.getPage()
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
