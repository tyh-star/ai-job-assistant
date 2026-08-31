package aijob.service.impl;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceKind;
import aijob.dto.JobRequirementDraft;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorAnalysisRequirement;
import aijob.dto.TailorAnalysisResponse;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.ResumeEvidenceItem;
import aijob.entity.User;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeEvidenceMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.JobRequirementExtractor;
import aijob.service.ResumeDocumentService;
import aijob.util.RequirementProfileIds;
import aijob.util.ResumeAnalysisInputFactory;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TailorAnalysisResponsibilityS2Test {

    private static final String EMAIL = "user@example.com";
    private static final String YAOHANG_JAVA_JD = """
            Junior Java Backend Developer

            Required qualifications:
            - Java and Spring Boot
            - REST APIs
            - Experience with Maven
            - Proficiency in SQL and MySQL
            - Knowledge of data structures and algorithms
            - Knowledge of Agile development
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
    void analyze_yaohangJavaJd_responsibilityStatusesAndCache() throws Exception {
        Map<String, String> uploadsBefore = snapshotUploads();
        stubOwned();
        when(resumeDocumentService.parseDocx(any())).thenReturn(resumeNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(drafts());

        TailorAnalysisResponse first = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse second = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse third = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse fourth = service.analyze(EMAIL, 153L, 9L);

        TailorAnalysisRequirement debug = byRequirement(first, "Debug, test and document");
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, debug.getStatus());
        assertEquals(RequirementCategory.RESPONSIBILITY, debug.getCategory());
        assertFalse(debug.getEvidenceNodeIds().contains("p-16"));

        TailorAnalysisRequirement tested = byRequirement(first, "well-documented");
        assertEquals(CoverageStatus.SUPPORTED, tested.getStatus());

        TailorAnalysisRequirement optimize = byRequirement(first, "Optimize application performance");
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, optimize.getStatus());
        assertNotEquals(CoverageStatus.SUPPORTED, optimize.getStatus());

        TailorAnalysisRequirement deploy = byRequirement(first, "Deploy and maintain");
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, deploy.getStatus());

        TailorAnalysisRequirement collaborate = byRequirement(first, "Collaborate through an Agile");
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, collaborate.getStatus());

        TailorAnalysisRequirement agileKnowledge = byRequirement(first, "Knowledge of Agile development");
        assertEquals(CoverageStatus.SUPPORTED, agileKnowledge.getStatus());
        assertEquals(RequirementCategory.HARD_SKILL, agileKnowledge.getCategory());
        assertNotEquals(agileKnowledge.getId(), collaborate.getId());

        TailorAnalysisRequirement build = byRequirement(first, "Build and integrate");
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, build.getStatus());

        TailorAnalysisRequirement design = byRequirement(first, "Design and implement");
        assertEquals(CoverageStatus.SUPPORTED, design.getStatus());

        TailorAnalysisRequirement cs = first.getRequirements().stream()
                .filter(item -> item.getRequirement().toLowerCase().contains("computer science"))
                .findFirst()
                .orElseThrow();
        assertEquals(RequirementCategory.QUALIFICATION, cs.getCategory());
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, cs.getStatus());
        assertEquals(EvidenceKind.EDUCATION, cs.getEvidenceKind());

        assertTrue(first.getRequirements().stream()
                .filter(item -> item.getRequirement().toLowerCase().contains("maven")
                        || item.getRequirement().toLowerCase().contains("data structure"))
                .allMatch(item -> RequirementCategory.HARD_SKILL.equals(item.getCategory())));
        assertNotNull(first.getDimensions().getHardSkills());
        assertTrue(first.getDimensions().getHardSkills() > 0);

        assertEquals(first.getOverallScore(), second.getOverallScore());
        assertEquals(first.getOverallScore(), third.getOverallScore());
        assertEquals(first.getOverallScore(), fourth.getOverallScore());
        assertEquals(requirementStatuses(first), requirementStatuses(fourth));
        assertEquals(RequirementProfileIds.SCHEMA_VERSION, 4);
        assertEquals(4, profileMapper.findByUserAndJob(1L, 153L).getSchemaVersion());
        verify(jobRequirementExtractor, times(1)).extract(any());
        assertEquals(uploadsBefore, snapshotUploads());
    }

    @Test
    void staleDebugMvcEvidenceDoesNotConfirmAndQuestionRemains() throws Exception {
        stubOwned();
        when(resumeDocumentService.parseDocx(any())).thenReturn(resumeNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(drafts());
        ResumeEvidenceItem stale = new ResumeEvidenceItem();
        stale.setId(44L);
        stale.setTopicKey("debug");
        stale.setTopicLabel("debug");
        stale.setCategory(RequirementCategory.RESPONSIBILITY);
        stale.setAnswerStatus("CONFIRMED_USED");
        stale.setActionText("Designed Spring Boot application using MVC architecture");
        when(resumeEvidenceMapper.findByResumeAndUser(9L, 1L)).thenReturn(List.of(stale));

        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisRequirement debug = byRequirement(analysis, "Debug, test and document");
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, debug.getStatus());
        assertNotEquals("USER_CONFIRMED", debug.getEvidenceAvailability());
        assertEquals(1, analysis.getEvidenceQuestions().stream()
                .filter(item -> item.getTopic().contains("Debug, test and document"))
                .count());
        assertTrue(analysis.getEvidenceQuestions().stream()
                .noneMatch(item -> "debug".equals(item.getTopic())
                        || "test".equals(item.getTopic())
                        || "document".equals(item.getTopic())));
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

    private static List<ResumeDocumentNode> resumeNodes() {
        List<ResumeDocumentNode> nodes = new ArrayList<>();
        nodes.add(new ResumeDocumentNode("p-0", "PARAGRAPH", "Yaohang Tang"));
        nodes.add(new ResumeDocumentNode("p-1", "PARAGRAPH", "yaohang@example.com"));
        nodes.add(new ResumeDocumentNode("p-2", "PARAGRAPH", "https://github.com/yaohang"));
        nodes.add(new ResumeDocumentNode("p-3", "PARAGRAPH", "TECHNICAL SKILLS"));
        nodes.add(new ResumeDocumentNode("p-11", "PARAGRAPH",
                "C/C++, Java, Python, Shell Script, SQL, HTML, Vue, Javascript, CSS, Understanding of Agile"));
        nodes.add(new ResumeDocumentNode("p-3b", "PARAGRAPH", "SUMMARY"));
        nodes.add(new ResumeDocumentNode("p-4", "PARAGRAPH",
                "Exceptional analytical and problem-solving skills with continuous thrive in optimizing program functionalities"));
        nodes.add(new ResumeDocumentNode("p-12", "PARAGRAPH", "RELEVANT PROJECTS"));
        nodes.add(new ResumeDocumentNode("p-13", "PARAGRAPH", "Prime Deals | 2024"));
        nodes.add(new ResumeDocumentNode("p-16", "PARAGRAPH",
                "Design and implement the application using Java Spring Boot with an MVC pattern."));
        nodes.add(new ResumeDocumentNode("p-17", "PARAGRAPH",
                "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements"));
        nodes.add(new ResumeDocumentNode("p-18", "PARAGRAPH", "SmartPC | 2023"));
        nodes.add(new ResumeDocumentNode("p-19", "PARAGRAPH",
                "Developed a Java Maven project that helps users select optimal components within a given budget"));
        nodes.add(new ResumeDocumentNode("p-20", "PARAGRAPH",
                "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)"));
        nodes.add(new ResumeDocumentNode("p-21", "PARAGRAPH",
                "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality"));
        nodes.add(new ResumeDocumentNode("p-21b", "PARAGRAPH", "Successfully deployed onto AWS"));
        nodes.add(new ResumeDocumentNode("p-22", "PARAGRAPH", "EXPERIENCE"));
        nodes.add(new ResumeDocumentNode("p-23", "PARAGRAPH", "Backend Intern | Acme | Jan 2024 – Present"));
        nodes.add(new ResumeDocumentNode("p-24", "PARAGRAPH",
                "●   Developed and integrated IP rate-limiting features."));
        nodes.add(new ResumeDocumentNode("p-30", "PARAGRAPH", "EDUCATION"));
        nodes.add(new ResumeDocumentNode("p-31", "PARAGRAPH", "York University"));
        nodes.add(new ResumeDocumentNode("p-32", "PARAGRAPH", "Bachelor of Computer Science"));
        nodes.add(new ResumeDocumentNode("p-33", "PARAGRAPH", "Sept 2023 – Present"));
        return nodes;
    }

    private static List<JobRequirementDraft> drafts() {
        return List.of(
                draft("Java and Spring Boot", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Java", "Spring Boot")),
                draft("REST APIs", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("REST APIs")),
                draft("Experience with Maven", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Maven")),
                draft("Proficiency in SQL and MySQL", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("SQL", "MySQL")),
                draft("Knowledge of data structures and algorithms", RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE, List.of("data structures", "algorithms")),
                draft("Knowledge of Agile development", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        List.of("Agile")),
                draft("Currently pursuing or recently completed a degree in Computer Science",
                        RequirementCategory.QUALIFICATION, RequirementPriority.MUST_HAVE,
                        List.of("Computer Science")),
                draft("Authorized to work in Canada", RequirementCategory.QUALIFICATION, RequirementPriority.MUST_HAVE,
                        List.of("Canada")),
                draft("Build and integrate RESTful APIs", RequirementCategory.RESPONSIBILITY, RequirementPriority.MUST_HAVE,
                        List.of("REST")),
                draft("Debug, test and document backend applications", RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE, List.of("debug", "test", "document")),
                draft("Deliver clean, well-documented and thoroughly tested code",
                        RequirementCategory.RESPONSIBILITY, RequirementPriority.MUST_HAVE, List.of("code")),
                draft("Optimize application performance", RequirementCategory.RESPONSIBILITY, RequirementPriority.MUST_HAVE,
                        List.of("optimize")),
                draft("Deploy and maintain applications on AWS", RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE, List.of("AWS")),
                draft("Collaborate through an Agile development process", RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.PREFERRED, List.of("Agile")),
                draft("Design and implement backend services", RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE, List.of("Design", "implement")),
                draft("Docker and Kubernetes", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        List.of("Docker", "Kubernetes")),
                draft("Strong communication and teamwork skills", RequirementCategory.SOFT_SKILL,
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

    private static TailorAnalysisRequirement byRequirement(TailorAnalysisResponse response, String needle) {
        return response.getRequirements().stream()
                .filter(item -> item.getRequirement().contains(needle))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> requirementStatuses(TailorAnalysisResponse response) {
        return response.getRequirements().stream()
                .map(item -> item.getId() + "|" + item.getStatus())
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
