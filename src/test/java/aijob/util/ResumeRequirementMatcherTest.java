package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceKind;
import aijob.dto.JobRequirementDraft;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.dto.TailorAnalysisRequirement;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResumeRequirementMatcherTest {

    @Test
    void javaSpringBootBulletIsSupported() {
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft(
                        "Java and Spring Boot",
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE,
                        List.of("Java", "Spring Boot")
                )),
                yaohangNodes()
        );

        assertEquals(1, results.size());
        assertEquals(CoverageStatus.SUPPORTED, results.get(0).getStatus());
        assertTrue(results.get(0).getEvidenceNodeIds().contains("p-11")
                || results.get(0).getEvidenceNodeIds().contains("p-16"));
        assertTrue(results.get(0).getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("Java") || quote.contains("Spring Boot")));
    }

    @Test
    void skillsOnlyJavaIsSkillsOnlyKind() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-3", "PARAGRAPH", "TECHNICAL SKILLS"),
                new ResumeDocumentNode("p-11", "PARAGRAPH", "Java, Python, SQL"),
                new ResumeDocumentNode("p-12", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-16", "PARAGRAPH", "Built a shopping cart with Vue.")
        );
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft("Java", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, List.of("Java"))),
                nodes
        );
        assertEquals(CoverageStatus.SUPPORTED, results.get(0).getStatus());
        assertEquals(EvidenceKind.SKILLS_ONLY, results.get(0).getEvidenceKind());
        assertEquals(List.of("p-11"), results.get(0).getEvidenceNodeIds());
    }

    @Test
    void springBootProjectBulletIsProjectEvidence() {
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft(
                        "Spring Boot",
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE,
                        List.of("Spring Boot")
                )),
                yaohangNodes()
        );
        assertEquals(CoverageStatus.SUPPORTED, results.get(0).getStatus());
        assertEquals(EvidenceKind.PROJECT_BULLET, results.get(0).getEvidenceKind());
        assertTrue(results.get(0).getEvidenceNodeIds().contains("p-16"));
    }

    @Test
    void skillsAndProjectTogetherUseProjectBulletKind() {
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft("Java", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, List.of("Java"))),
                yaohangNodes()
        );
        assertEquals(CoverageStatus.SUPPORTED, results.get(0).getStatus());
        assertEquals(EvidenceKind.PROJECT_BULLET, results.get(0).getEvidenceKind());
        assertTrue(results.get(0).getEvidenceNodeIds().contains("p-11"));
        assertTrue(results.get(0).getEvidenceNodeIds().contains("p-16"));
    }

    @Test
    void dockerPresentKubernetesMissingAreIndependent() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-12", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-16", "PARAGRAPH", "Packaged the service with Docker Compose for local demos.")
        );
        List<JobRequirementDraft> drafts = RequirementAtomizer.atomize(List.of(
                draft("Docker and Kubernetes", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        List.of("Docker", "Kubernetes"))
        ));
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(drafts, nodes);
        assertEquals(2, results.size());
        assertEquals(CoverageStatus.SUPPORTED, results.get(0).getStatus());
        assertEquals("Docker", results.get(0).getRequirement());
        assertEquals(EvidenceKind.PROJECT_BULLET, results.get(0).getEvidenceKind());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, results.get(1).getStatus());
        assertEquals("Kubernetes", results.get(1).getRequirement());
        assertEquals(
                RequirementProfileIds.stableId(
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.PREFERRED,
                        "Docker"
                ),
                results.get(0).getId()
        );
        assertEquals(
                RequirementProfileIds.stableId(
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.PREFERRED,
                        "Kubernetes"
                ),
                results.get(1).getId()
        );
    }

    @Test
    void requirementIdsAreStableAcrossDraftOrder() {
        JobRequirementDraft java = draft(
                "Java",
                RequirementCategory.HARD_SKILL,
                RequirementPriority.MUST_HAVE,
                List.of("Java")
        );
        JobRequirementDraft docker = draft(
                "Docker",
                RequirementCategory.HARD_SKILL,
                RequirementPriority.PREFERRED,
                List.of("Docker")
        );
        List<TailorAnalysisRequirement> original = ResumeRequirementMatcher.match(
                List.of(java, docker),
                yaohangNodes()
        );
        List<TailorAnalysisRequirement> reversed = ResumeRequirementMatcher.match(
                List.of(docker, java),
                yaohangNodes()
        );
        assertEquals(original.get(0).getId(), reversed.get(1).getId());
        assertEquals(original.get(1).getId(), reversed.get(0).getId());
    }

    @Test
    void dockerWithoutResumeEvidenceIsMissing() {
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft(
                        "Docker",
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE,
                        List.of("Docker")
                )),
                yaohangNodes()
        );

        assertEquals(CoverageStatus.MISSING_EVIDENCE, results.get(0).getStatus());
        assertTrue(results.get(0).getEvidenceNodeIds().isEmpty());
    }

    @Test
    void backendExperienceWithoutDockerIsNotSupported() {
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft(
                        "Docker",
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.PREFERRED,
                        List.of("Docker")
                )),
                yaohangNodes()
        );

        assertFalse(CoverageStatus.SUPPORTED.equals(results.get(0).getStatus()));
        assertEquals(CoverageStatus.MISSING_EVIDENCE, results.get(0).getStatus());
    }

    @Test
    void contactHeadingAndDateCannotBeEvidence() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "Yaohang Tang"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "java@example.com"),
                new ResumeDocumentNode("p-2", "PARAGRAPH", "https://github.com/yaohang"),
                new ResumeDocumentNode("p-3", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-4", "PARAGRAPH", "Docker Intern | Jan 2024 – Present"),
                new ResumeDocumentNode("p-5", "PARAGRAPH", "●   Built billing reports for finance teams.")
        );
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(
                        draft("Java", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, List.of("Java")),
                        draft("Docker", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, List.of("Docker"))
                ),
                nodes
        );

        assertEquals(CoverageStatus.MISSING_EVIDENCE, results.get(0).getStatus());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, results.get(1).getStatus());
    }

    @Test
    void invalidNodeIdAndMismatchedQuoteAreStripped() {
        TailorAnalysisRequirement requirement = new TailorAnalysisRequirement();
        requirement.setStatus(CoverageStatus.SUPPORTED);
        requirement.setEvidenceNodeIds(List.of("missing-node", "p-16"));
        requirement.setEvidenceQuotes(List.of("invented quote", "Kubernetes cluster"));

        ResumeRequirementMatcher.sanitizeEvidence(requirement, yaohangNodes());

        assertTrue(requirement.getEvidenceNodeIds().isEmpty());
        assertTrue(requirement.getEvidenceQuotes().isEmpty());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, requirement.getStatus());
    }

    @Test
    void validQuoteFromRealNodeIsKept() {
        TailorAnalysisRequirement requirement = new TailorAnalysisRequirement();
        requirement.setStatus(CoverageStatus.SUPPORTED);
        requirement.setEvidenceNodeIds(List.of("p-16"));
        requirement.setEvidenceQuotes(List.of(
                "Design and implement the application using Java Spring Boot with an MVC pattern."
        ));

        ResumeRequirementMatcher.sanitizeEvidence(requirement, yaohangNodes());

        assertEquals(List.of("p-16"), requirement.getEvidenceNodeIds());
        assertEquals(CoverageStatus.SUPPORTED, requirement.getStatus());
    }

    @Test
    void mvcArchitectureMatchesMvcPatternProjectBullet() {
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft(
                        "Implement features using MVC architecture",
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE,
                        List.of("MVC architecture")
                )),
                yaohangNodes()
        );
        assertEquals(CoverageStatus.SUPPORTED, results.get(0).getStatus());
        assertEquals(EvidenceKind.PROJECT_BULLET, results.get(0).getEvidenceKind());
        assertTrue(results.get(0).getEvidenceNodeIds().contains("p-16"));
        assertTrue(results.get(0).getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("MVC pattern")));
    }

    @Test
    void restfulApisMatchRestApis() {
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft(
                        "REST APIs",
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE,
                        List.of("REST APIs")
                )),
                yaohangNodes()
        );
        assertEquals(CoverageStatus.SUPPORTED, results.get(0).getStatus());
        assertTrue(results.get(0).getEvidenceNodeIds().contains("p-17"));
        assertTrue(results.get(0).getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("RESTful APIs")));
    }

    @Test
    void designApplicationWithoutMvcDoesNotSupportMvc() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-12", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-16", "PARAGRAPH",
                        "Design and implement the application for local checkout.")
        );
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft(
                        "Implement features using MVC architecture",
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE,
                        List.of("MVC architecture")
                )),
                nodes
        );
        assertEquals(CoverageStatus.MISSING_EVIDENCE, results.get(0).getStatus());
        assertTrue(results.get(0).getEvidenceNodeIds().isEmpty());
    }

    @Test
    void inProgressComputerScienceDegreeIsPartiallySupported() {
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft(
                        "Pursuing or completed a degree in Computer Science or related field",
                        RequirementCategory.QUALIFICATION,
                        RequirementPriority.PREFERRED,
                        List.of("degree", "Computer Science")
                )),
                yaohangNodes()
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, results.get(0).getStatus());
        assertEquals(EvidenceKind.EDUCATION, results.get(0).getEvidenceKind());
        assertEquals(QualificationMatcher.CURRENTLY_PURSUING, results.get(0).getCoverageReason());
        assertTrue(results.get(0).getEvidenceNodeIds().contains("p-32")
                || results.get(0).getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("Bachelor of Computer Science")));
        assertFalse(results.get(0).getCoverageReason() != null
                && results.get(0).getCoverageReason().toLowerCase().contains("graduat"));
    }

    @Test
    void structuredEducationMatchesWhenDocxHasNoEducationSection() {
        ResumeStructure structure = ResumeStructure.empty();
        ResumeStructure.ResumeEducation education = new ResumeStructure.ResumeEducation();
        education.setSchool("York University");
        education.setDegree("Bachelor of Computer Science");
        education.setDates("Sept 2023 – Present");
        structure.getEducation().add(education);

        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft(
                        "Pursuing or completed a degree in Computer Science or related field",
                        RequirementCategory.QUALIFICATION,
                        RequirementPriority.MUST_HAVE,
                        List.of("Computer Science")
                )),
                List.of(
                        new ResumeDocumentNode("p-12", "PARAGRAPH", "RELEVANT PROJECTS"),
                        new ResumeDocumentNode("p-16", "PARAGRAPH",
                                "Design and implement the application using Java Spring Boot with an MVC pattern.")
                ),
                structure
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, results.get(0).getStatus());
        assertEquals(QualificationMatcher.CURRENTLY_PURSUING, results.get(0).getCoverageReason());
        assertEquals(EvidenceKind.EDUCATION, results.get(0).getEvidenceKind());
        assertEquals(List.of("edu-structure-0"), results.get(0).getEvidenceNodeIds());
        assertTrue(results.get(0).getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("Bachelor of Computer Science")));
        assertTrue(results.get(0).getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("Present")));
    }

    @Test
    void structuredEducationUsesSyntheticIdEvenWhenSourceBlockIdsAreLayoutBlocks() {
        ResumeStructure structure = ResumeStructure.empty();
        ResumeStructure.ResumeEducation education = new ResumeStructure.ResumeEducation();
        education.setSchool("York University");
        education.setDegree("Bachelor of Computer Science");
        education.setDates("Sept 2023 – Present");
        education.setSourceBlockIds(List.of("b-p1-0"));
        structure.getEducation().add(education);

        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                List.of(draft(
                        "Currently pursuing or recently completed a degree in Computer Science",
                        RequirementCategory.QUALIFICATION,
                        RequirementPriority.MUST_HAVE,
                        List.of("Computer Science")
                )),
                List.of(
                        new ResumeDocumentNode("p-12", "PARAGRAPH", "RELEVANT PROJECTS"),
                        new ResumeDocumentNode("p-16", "PARAGRAPH",
                                "Design and implement the application using Java Spring Boot with an MVC pattern.")
                ),
                structure
        );

        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, results.get(0).getStatus());
        assertEquals(EvidenceKind.EDUCATION, results.get(0).getEvidenceKind());
        assertEquals(QualificationMatcher.CURRENTLY_PURSUING, results.get(0).getCoverageReason());
        assertEquals(List.of("edu-structure-0"), results.get(0).getEvidenceNodeIds());
        assertFalse(results.get(0).getEvidenceNodeIds().stream().anyMatch(id -> id.startsWith("b-p")));
        assertTrue(results.get(0).getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("Bachelor of Computer Science")));
        assertTrue(results.get(0).getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("Present")));
    }

    @Test
    void sanitizeKeepsStructuredEducationWhenMatchingNodesAreParagraphs() {
        TailorAnalysisRequirement requirement = new TailorAnalysisRequirement();
        requirement.setCategory(RequirementCategory.QUALIFICATION);
        requirement.setStatus(CoverageStatus.PARTIALLY_SUPPORTED);
        requirement.setEvidenceKind(EvidenceKind.EDUCATION);
        requirement.setEvidenceNodeIds(List.of("edu-structure-0"));
        requirement.setEvidenceQuotes(List.of(
                "Bachelor of Computer Science",
                "Present"
        ));

        Map<String, AnalysisEvidenceSource> sources = new LinkedHashMap<>();
        sources.putAll(ResumeAnalysisInputFactory.matchingNodeSources(List.of(
                new ResumeDocumentNode("p-16", "PARAGRAPH",
                        "Design and implement the application using Java Spring Boot with an MVC pattern.")
        )));
        sources.put("edu-structure-0", new AnalysisEvidenceSource(
                "edu-structure-0",
                "York University Bachelor of Computer Science Sept 2023 – Present",
                AnalysisEvidenceSource.Kind.STRUCTURED_EDUCATION
        ));

        ResumeRequirementMatcher.sanitizeEvidence(requirement, sources);

        assertEquals(List.of("edu-structure-0"), requirement.getEvidenceNodeIds());
        assertTrue(requirement.getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("Bachelor of Computer Science")));
        assertTrue(requirement.getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("Present")));
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, requirement.getStatus());
        assertEquals(EvidenceKind.EDUCATION, requirement.getEvidenceKind());
    }

    @Test
    void sanitizeRejectsUnknownLayoutPrefixEvenWithEducationQuote() {
        TailorAnalysisRequirement requirement = new TailorAnalysisRequirement();
        requirement.setCategory(RequirementCategory.QUALIFICATION);
        requirement.setStatus(CoverageStatus.PARTIALLY_SUPPORTED);
        requirement.setEvidenceNodeIds(List.of("b-p1-99"));
        requirement.setEvidenceQuotes(List.of("Bachelor of Computer Science"));

        ResumeRequirementMatcher.sanitizeEvidence(
                requirement,
                ResumeAnalysisInputFactory.matchingNodeSources(yaohangNodes())
        );

        assertTrue(requirement.getEvidenceNodeIds().isEmpty());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, requirement.getStatus());
    }

    @Test
    void sanitizeKeepsVerifiedLayoutBlockQuote() {
        TailorAnalysisRequirement requirement = new TailorAnalysisRequirement();
        requirement.setStatus(CoverageStatus.SUPPORTED);
        requirement.setEvidenceNodeIds(List.of("b-p1-0"));
        requirement.setEvidenceQuotes(List.of("York University"));
        Map<String, AnalysisEvidenceSource> sources = Map.of(
                "b-p1-0",
                new AnalysisEvidenceSource(
                        "b-p1-0",
                        "York University",
                        AnalysisEvidenceSource.Kind.LAYOUT_BLOCK
                )
        );

        ResumeRequirementMatcher.sanitizeEvidence(requirement, sources);

        assertEquals(List.of("b-p1-0"), requirement.getEvidenceNodeIds());
        assertEquals(CoverageStatus.SUPPORTED, requirement.getStatus());
    }

    private JobRequirementDraft draft(
            String requirement,
            String category,
            String priority,
            List<String> terms) {
        JobRequirementDraft draft = new JobRequirementDraft();
        draft.setRequirement(requirement);
        draft.setCategory(category);
        draft.setPriority(priority);
        draft.setExactTerms(terms);
        return draft;
    }

    public static List<ResumeDocumentNode> yaohangNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "Yaohang Tang"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "yaohang@example.com"),
                new ResumeDocumentNode("p-2", "PARAGRAPH", "https://github.com/yaohang"),
                new ResumeDocumentNode("p-3", "PARAGRAPH", "TECHNICAL SKILLS"),
                new ResumeDocumentNode("p-11", "PARAGRAPH",
                        "C/C++, Java, Python, Shell Script, SQL, HTML, Vue, Javascript, CSS"),
                new ResumeDocumentNode("p-12", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-13", "PARAGRAPH", "Prime Deals | 2024"),
                new ResumeDocumentNode("p-16", "PARAGRAPH",
                        "Design and implement the application using Java Spring Boot with an MVC pattern."),
                new ResumeDocumentNode("p-17", "PARAGRAPH",
                        "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements"),
                new ResumeDocumentNode("p-18", "PARAGRAPH", "SmartPC | 2023"),
                new ResumeDocumentNode("p-19", "PARAGRAPH",
                        "Developed a Java Maven project that helps users select optimal components within a given budget"),
                new ResumeDocumentNode("p-20", "PARAGRAPH",
                        "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)"),
                new ResumeDocumentNode("p-21", "PARAGRAPH",
                        "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality"),
                new ResumeDocumentNode("p-22", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-23", "PARAGRAPH", "Backend Intern | Acme | Jan 2024 – Present"),
                new ResumeDocumentNode("p-24", "PARAGRAPH",
                        "●   Developed and integrated IP rate-limiting features."),
                new ResumeDocumentNode("p-30", "PARAGRAPH", "EDUCATION"),
                new ResumeDocumentNode("p-31", "PARAGRAPH", "York University"),
                new ResumeDocumentNode("p-32", "PARAGRAPH", "Bachelor of Computer Science"),
                new ResumeDocumentNode("p-33", "PARAGRAPH", "Sept 2023 – Present")
        );
    }
}
