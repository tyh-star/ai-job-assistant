package aijob.service.impl;

import aijob.dto.JobRequirementDraft;
import aijob.dto.QuestionType;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.TailorAnalysisRequirement;
import aijob.dto.TailorAnalysisResponse;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.User;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeEvidenceMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.JobRequirementExtractor;
import aijob.service.ResumeDocumentService;
import aijob.util.RequirementAtomizer;
import aijob.util.RequirementProfileIds;
import aijob.util.RequirementTaxonomySanitizer;
import aijob.util.ResumeAnalysisInputFactory;
import aijob.util.ResumeRequirementMatcherTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TailorAnalysisTaxonomyS1Test {

    private static final String EMAIL = "user@example.com";
    private static final String YAOHANG_JAVA_JD = """
            Junior Java Backend Developer

            Required qualifications:
            - Java and Spring Boot
            - REST APIs
            - Experience with Maven
            - Proficiency in SQL and MySQL
            - Knowledge of data structures and algorithms
            - Currently pursuing or recently completed a degree in Computer Science
            - Authorized to work in Canada

            Responsibilities:
            - Build and integrate RESTful APIs
            - Debug, test and document backend applications
            - Deliver clean, well-documented and thoroughly tested code
            - Optimize application performance
            - Deploy and maintain applications on AWS
            - Collaborate through an Agile development process
            - Design and implement backend services

            Preferred:
            - Docker and Kubernetes
            - Strong communication and teamwork skills
            """;

    @Mock
    private UserMapper userMapper;
    @Mock
    private JobMapper jobMapper;
    @Mock
    private ResumeMapper resumeMapper;
    @Mock
    private ResumeEvidenceMapper resumeEvidenceMapper;
    @Mock
    private ResumeDocumentService resumeDocumentService;
    @Mock
    private JobRequirementExtractor jobRequirementExtractor;

    @TempDir
    Path tempDir;

    private TailorAnalysisServiceImplTest.InMemoryJobRequirementProfileMapper profileMapper;
    private TailorAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        profileMapper = new TailorAnalysisServiceImplTest.InMemoryJobRequirementProfileMapper();
        ObjectMapper objectMapper = new ObjectMapper();
        service = new TailorAnalysisServiceImpl(
                userMapper,
                jobMapper,
                resumeMapper,
                resumeEvidenceMapper,
                resumeDocumentService,
                jobRequirementExtractor,
                profileMapper,
                objectMapper,
                new ResumeAnalysisInputFactory(resumeMapper, resumeDocumentService, objectMapper),
                "gpt-4o"
        );
    }

    @Test
    void sanitizeDrafts_correctsWrongLlmCategories() {
        List<JobRequirementDraft> cleaned = service.sanitizeDrafts(wrongLlmDrafts(), YAOHANG_JAVA_JD);
        assertEquals(RequirementCategory.HARD_SKILL, categoryOf(cleaned, "Maven"));
        assertEquals(RequirementCategory.HARD_SKILL, categoryOf(cleaned, "data structures"));
        assertEquals(RequirementCategory.RESPONSIBILITY, categoryOf(cleaned, "tested"));
        assertEquals(RequirementCategory.QUALIFICATION, categoryOf(cleaned, "Computer Science"));
        assertEquals(RequirementCategory.SOFT_SKILL, categoryOf(cleaned, "communication"));
        assertTrue(cleaned.stream().noneMatch(item ->
                item.getRequirement().toLowerCase().contains("maven")
                        && RequirementCategory.QUALIFICATION.equals(item.getCategory())));
    }

    @Test
    void pipeline_keepsDutyGroupsAndSplitsTechLists() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(
                service.sanitizeDrafts(wrongLlmDrafts(), YAOHANG_JAVA_JD)
        );
        RequirementTaxonomySanitizer.apply(result);

        assertEquals(1, countContaining(result, "Debug, test and document"));
        assertEquals(1, countContaining(result, "Deploy and maintain"));
        assertTrue(result.stream().anyMatch(item -> "Java".equals(item.getRequirement())));
        assertTrue(result.stream().anyMatch(item -> "Spring Boot".equals(item.getRequirement())));
        assertTrue(result.stream().anyMatch(item -> "SQL".equals(item.getRequirement())
                || "MySQL".equals(item.getRequirement())));
        assertEquals(2, result.stream().filter(item ->
                "Docker".equals(item.getRequirement()) || "Kubernetes".equals(item.getRequirement())).count());
        assertTrue(result.stream().filter(item -> RequirementCategory.QUALIFICATION.equals(item.getCategory()))
                .allMatch(item -> item.getRequirement().toLowerCase().contains("degree")
                        || item.getRequirement().toLowerCase().contains("authorized")));
    }

    @Test
    void analyze_yaohangJavaJd_wrongExtractor_usesServerTaxonomyAndCache() throws Exception {
        Map<String, String> uploadsBefore = snapshotUploads();
        stubOwned();
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(wrongLlmDrafts());

        TailorAnalysisResponse first = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse second = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse third = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse fourth = service.analyze(EMAIL, 153L, 9L);

        assertNotNull(first.getDimensions().getHardSkills());
        assertTrue(first.getDimensions().getHardSkills() > 0);
        assertTrue(first.getRequirements().stream()
                .filter(item -> containsIgnore(item, "Maven")
                        || containsIgnore(item, "Java")
                        || containsIgnore(item, "Spring")
                        || containsIgnore(item, "SQL")
                        || containsIgnore(item, "MySQL")
                        || containsIgnore(item, "data structure")
                        || containsIgnore(item, "algorithm"))
                .noneMatch(item -> RequirementCategory.QUALIFICATION.equals(item.getCategory())));
        TailorAnalysisRequirement cs = first.getRequirements().stream()
                .filter(item -> item.getRequirement().toLowerCase().contains("computer science"))
                .findFirst()
                .orElseThrow();
        assertEquals(RequirementCategory.QUALIFICATION, cs.getCategory());
        TailorAnalysisRequirement tested = first.getRequirements().stream()
                .filter(item -> item.getRequirement().toLowerCase().contains("tested")
                        || item.getRequirement().toLowerCase().contains("documented"))
                .findFirst()
                .orElseThrow();
        assertEquals(RequirementCategory.RESPONSIBILITY, tested.getCategory());
        assertEquals(1, first.getRequirements().stream()
                .filter(item -> item.getRequirement().toLowerCase().contains("debug")).count());
        assertEquals(1, first.getRequirements().stream()
                .filter(item -> item.getRequirement().toLowerCase().contains("deploy")
                        && item.getRequirement().toLowerCase().contains("maintain")).count());
        assertTrue(first.getRequirements().stream()
                .filter(item -> RequirementCategory.QUALIFICATION.equals(item.getCategory()))
                .allMatch(item -> item.getRequirement().toLowerCase().contains("degree")
                        || item.getRequirement().toLowerCase().contains("authorized")
                        || item.getRequirement().toLowerCase().contains("certif")));
        assertTrue(first.getEvidenceQuestions().stream().noneMatch(item ->
                QuestionType.EDUCATION_CONFIRMATION.equals(item.getQuestionType())
                        && item.getTopic() != null
                        && item.getTopic().toLowerCase().contains("maven")));
        assertEquals(first.getOverallScore(), second.getOverallScore());
        assertEquals(first.getOverallScore(), third.getOverallScore());
        assertEquals(first.getOverallScore(), fourth.getOverallScore());
        assertEquals(requirementStatuses(first), requirementStatuses(fourth));
        assertEquals(requirementCategories(first), requirementCategories(fourth));
        assertEquals(RequirementProfileIds.SCHEMA_VERSION, 4);
        assertEquals(4, profileMapper.findByUserAndJob(1L, 153L).getSchemaVersion());
        verify(jobRequirementExtractor, times(1)).extract(any());
        assertEquals(uploadsBefore, snapshotUploads());
    }

    private void stubOwned() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job job = new Job();
        job.setId(153L);
        job.setUserId(1L);
        job.setTitle("Junior Java Backend Developer");
        job.setDescription(YAOHANG_JAVA_JD);
        when(jobMapper.findById(153L)).thenReturn(job);
        Path file = tempDir.resolve("resume.docx");
        Files.writeString(file, "placeholder");
        Resume resume = new Resume();
        resume.setId(9L);
        resume.setUserId(1L);
        resume.setFileType("docx");
        resume.setFilePath(file.toString());
        resume.setOrigin("UPLOAD");
        resume.setExtractedText("Yaohang Tang\nJava Spring Boot REST Maven");
        when(resumeMapper.findById(9L)).thenReturn(resume);
    }

    private static List<JobRequirementDraft> wrongLlmDrafts() {
        return List.of(
                draft("Java and Spring Boot", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Java", "Spring Boot")),
                draft("REST APIs", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("REST APIs")),
                draft("Experience with Maven", RequirementCategory.QUALIFICATION, RequirementPriority.MUST_HAVE,
                        List.of("Maven")),
                draft("Proficiency in SQL and MySQL", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("SQL", "MySQL")),
                draft("Knowledge of data structures and algorithms", RequirementCategory.QUALIFICATION,
                        RequirementPriority.MUST_HAVE, List.of("data structures", "algorithms")),
                draft("Currently pursuing or recently completed a degree in Computer Science",
                        RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Computer Science")),
                draft("Authorized to work in Canada", RequirementCategory.SOFT_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Canada")),
                draft("Build and integrate RESTful APIs", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("REST")),
                draft("Debug, test and document backend applications", RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE, List.of("debug", "test", "document")),
                draft("Deliver clean, well-documented and thoroughly tested code",
                        RequirementCategory.QUALIFICATION, RequirementPriority.MUST_HAVE, List.of("code")),
                draft("Optimize application performance", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("optimize")),
                draft("Deploy and maintain applications on AWS", RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE, List.of("AWS")),
                draft("Collaborate through an Agile development process", RequirementCategory.SOFT_SKILL,
                        RequirementPriority.PREFERRED, List.of("Agile")),
                draft("Design and implement backend services", RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE, List.of("Design", "implement")),
                draft("Docker and Kubernetes", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        List.of("Docker", "Kubernetes")),
                draft("Strong communication and teamwork skills", RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.PREFERRED, List.of("communication"))
        );
    }

    private static JobRequirementDraft draft(
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

    private static String categoryOf(List<JobRequirementDraft> drafts, String needle) {
        return drafts.stream()
                .filter(item -> item.getRequirement().toLowerCase().contains(needle.toLowerCase())
                        || item.getExactTerms().stream().anyMatch(term ->
                        term.toLowerCase().contains(needle.toLowerCase())))
                .findFirst()
                .orElseThrow()
                .getCategory();
    }

    private static long countContaining(List<JobRequirementDraft> drafts, String needle) {
        return drafts.stream()
                .filter(item -> item.getRequirement().contains(needle))
                .count();
    }

    private static boolean containsIgnore(TailorAnalysisRequirement item, String needle) {
        String lower = needle.toLowerCase();
        if (item.getRequirement() != null && item.getRequirement().toLowerCase().contains(lower)) {
            return true;
        }
        return item.getExactTerms() != null && item.getExactTerms().stream()
                .anyMatch(term -> term.toLowerCase().contains(lower));
    }

    private static List<String> requirementStatuses(TailorAnalysisResponse response) {
        return response.getRequirements().stream()
                .map(item -> item.getId() + "|" + item.getStatus())
                .toList();
    }

    private static List<String> requirementCategories(TailorAnalysisResponse response) {
        return response.getRequirements().stream()
                .map(item -> item.getId() + "|" + item.getCategory())
                .toList();
    }

    private static Map<String, String> snapshotUploads() throws Exception {
        Path root = Path.of("uploads");
        Map<String, String> snapshot = new LinkedHashMap<>();
        if (!Files.exists(root)) {
            return snapshot;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : files) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                String hash;
                try {
                    digest.reset();
                    hash = java.util.HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
                } catch (Exception ex) {
                    hash = "locked";
                }
                snapshot.put(
                        rel,
                        Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis() + "|" + hash
                );
            }
        }
        return snapshot;
    }
}
