package aijob.service.impl;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceAvailability;
import aijob.dto.EvidenceQuestion;
import aijob.dto.JobRequirementDraft;
import aijob.dto.QuestionType;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorAnalysisRequirement;
import aijob.dto.TailorAnalysisResponse;
import aijob.entity.Job;
import aijob.entity.JobRequirementProfile;
import aijob.entity.Resume;
import aijob.entity.ResumeEvidenceItem;
import aijob.entity.User;
import aijob.exception.JobNotFoundException;
import aijob.exception.ResumeNotFoundException;
import aijob.exception.ResumeTextNotAvailableException;
import aijob.mapper.JobMapper;
import aijob.mapper.JobRequirementProfileMapper;
import aijob.mapper.ResumeEvidenceMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.JobRequirementExtractor;
import aijob.service.ResumeDocumentService;
import aijob.util.JobDescriptionHash;
import aijob.util.RequirementProfileIds;
import aijob.util.ResumeAnalysisInputFactory;
import aijob.util.ResumeRequirementMatcherTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DuplicateKeyException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TailorAnalysisServiceImplTest {

    private static final String EMAIL = "user@example.com";
    private static final String JUNIOR_JAVA_JD = """
            Junior Java Backend Developer

            Required qualifications:
            - Java and Spring Boot
            - REST APIs
            - Maven

            Responsibilities:
            - Design and implement backend services

            Preferred:
            - Docker and Kubernetes
            """;

    private static final String CURRENT_JD = """
            Junior Java Backend Developer

            Required qualifications:
            - Java and Spring Boot
            - REST APIs
            - Maven
            - Implement features using MVC architecture
            - Pursuing or completed a degree in Computer Science or related field

            Responsibilities:
            - Design and implement backend services

            Preferred:
            - Docker and Kubernetes
            - Agile
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

    private InMemoryJobRequirementProfileMapper profileMapper;
    private TailorAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        profileMapper = new InMemoryJobRequirementProfileMapper();
        service = newService(profileMapper);
    }

    private TailorAnalysisServiceImpl newService(JobRequirementProfileMapper profiles) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new TailorAnalysisServiceImpl(
                userMapper,
                jobMapper,
                resumeMapper,
                resumeEvidenceMapper,
                resumeDocumentService,
                jobRequirementExtractor,
                profiles,
                objectMapper,
                new ResumeAnalysisInputFactory(resumeMapper, resumeDocumentService, objectMapper),
                "gpt-4o"
        );
    }

    @Test
    void analyze_splitsHardSkillResponsibilityAndQualificationAndScoresDeterministically()
            throws Exception {
        stubOwned(docxResume(), ownedJob());
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts());

        TailorAnalysisResponse first = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse second = service.analyze(EMAIL, 153L, 9L);

        Set<String> categories = first.getRequirements().stream()
                .map(item -> item.getCategory())
                .collect(Collectors.toSet());
        assertTrue(categories.contains(RequirementCategory.HARD_SKILL));
        assertTrue(categories.contains(RequirementCategory.RESPONSIBILITY));
        assertTrue(categories.contains(RequirementCategory.QUALIFICATION)
                || first.getRequirements().stream().anyMatch(item ->
                RequirementCategory.HARD_SKILL.equals(item.getCategory())));

        TailorAnalysisRequirement java = byTerm(first, "Java");
        TailorAnalysisRequirement springBoot = byTerm(first, "Spring Boot");
        TailorAnalysisRequirement docker = byTerm(first, "Docker");
        TailorAnalysisRequirement kubernetes = byTerm(first, "Kubernetes");
        assertEquals("Java", java.getRequirement());
        assertEquals("Spring Boot", springBoot.getRequirement());
        assertEquals(CoverageStatus.SUPPORTED, java.getStatus());
        assertEquals(CoverageStatus.SUPPORTED, springBoot.getStatus());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, docker.getStatus());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, kubernetes.getStatus());
        assertFalse(CoverageStatus.SUPPORTED.equals(docker.getStatus()));

        long hardSkills = first.getRequirements().stream()
                .filter(item -> RequirementCategory.HARD_SKILL.equals(item.getCategory()))
                .count();
        long responsibilities = first.getRequirements().stream()
                .filter(item -> RequirementCategory.RESPONSIBILITY.equals(item.getCategory()))
                .count();
        assertTrue(hardSkills >= 3);
        assertTrue(responsibilities >= 1);
        assertNull(first.getDimensions().getQualifications());
        assertEquals(first.getOverallScore(), second.getOverallScore());
        assertEquals(first.getDimensions().getHardSkills(), second.getDimensions().getHardSkills());
        assertEquals(requirementIds(first), requirementIds(second));
        assertEquals(requirementStatuses(first), requirementStatuses(second));
        assertEquals(first.getDimensions(), second.getDimensions());
        verify(jobRequirementExtractor, times(1)).extract(any());
        assertTrue(first.isRequirementsFixed());
        JobRequirementProfile stored = profileMapper.findByUserAndJob(1L, 153L);
        assertFalse(stored.getRequirementsJson().contains("overallScore"));
        assertFalse(stored.getRequirementsJson().contains("evidenceNodeIds"));
        assertFalse(stored.getRequirementsJson().contains("coverageStatus"));
        assertEquals(
                first.getSummary().getMissingMustHaveCount()
                        + first.getSummary().getMissingPreferredCount()
                        + (int) first.getRequirements().stream()
                        .filter(item -> CoverageStatus.MISSING_EVIDENCE.equals(item.getStatus())
                                && RequirementPriority.OPTIONAL.equals(item.getPriority()))
                        .count(),
                first.getSummary().getMissingEvidenceCount()
        );
        assertEquals(
                (int) first.getRequirements().stream()
                        .filter(item -> CoverageStatus.MISSING_EVIDENCE.equals(item.getStatus()))
                        .count(),
                first.getSummary().getMissingEvidenceCount()
        );
        assertTrue(first.getSummary().getTotalMustHaveCount() >= 1);
        assertTrue(first.getSummary().getMissingEvidenceCount() >= 1);

        String joinedTerms = first.getRequirements().stream()
                .flatMap(item -> item.getExactTerms().stream())
                .map(String::toLowerCase)
                .collect(Collectors.joining(" "));
        assertFalse(joinedTerms.contains("design"));
        assertFalse(joinedTerms.contains("implement"));
        assertTrue(joinedTerms.contains("spring boot") || joinedTerms.contains("java"));
        assertEquals(79, first.getOverallScore());
        assertEquals(EvidenceAvailability.NONE, docker.getEvidenceAvailability());
        List<String> questionTopics = first.getEvidenceQuestions().stream()
                .map(EvidenceQuestion::getTopic)
                .toList();
        assertTrue(questionTopics.contains("Docker"));
        assertTrue(questionTopics.contains("Kubernetes"));
        int dockerIndex = questionTopics.indexOf("Docker");
        int kubernetesIndex = questionTopics.indexOf("Kubernetes");
        assertTrue(dockerIndex >= 0 && kubernetesIndex > dockerIndex);

        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(first);
        assertTrue(json.contains("\"resumeId\" : 9"));
        assertTrue(json.contains("\"jobId\" : 153"));
        assertTrue(json.contains("SUPPORTED") || json.contains("MISSING_EVIDENCE"));
    }

    @Test
    void analyze_otherUsersJob_is404() {
        User user = user(1L);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job foreign = ownedJob();
        foreign.setUserId(2L);
        when(jobMapper.findById(153L)).thenReturn(foreign);

        assertThrows(JobNotFoundException.class, () -> service.analyze(EMAIL, 153L, 9L));
    }

    @Test
    void analyze_otherUsersResume_is404() {
        stubUserAndJob();
        Resume foreign = docxResume();
        foreign.setUserId(2L);
        when(resumeMapper.findById(9L)).thenReturn(foreign);

        assertThrows(ResumeNotFoundException.class, () -> service.analyze(EMAIL, 153L, 9L));
    }

    @Test
    void analyze_blankExtractedText_is422() {
        stubUserAndJob();
        Resume resume = docxResume();
        resume.setExtractedText("  ");
        when(resumeMapper.findById(9L)).thenReturn(resume);

        assertThrows(ResumeTextNotAvailableException.class, () -> service.analyze(EMAIL, 153L, 9L));
    }

    @Test
    void userConfirmedDockerDoesNotRaiseScoreOrChangeCoverage() throws Exception {
        stubOwned(docxResume(), ownedJob());
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts());
        ResumeEvidenceItem docker = new ResumeEvidenceItem();
        docker.setId(12L);
        docker.setTopicKey("docker");
        docker.setTopicLabel("Docker");
        docker.setCategory(RequirementCategory.HARD_SKILL);
        docker.setAnswerStatus("CONFIRMED_USED");
        docker.setActionText("Containerized the Spring Boot application with Docker");
        when(resumeEvidenceMapper.findByResumeAndUser(9L, 1L)).thenReturn(List.of(docker));

        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisRequirement dockerReq = byTerm(analysis, "Docker");
        assertEquals(79, analysis.getOverallScore());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, dockerReq.getStatus());
        assertEquals(EvidenceAvailability.USER_CONFIRMED, dockerReq.getEvidenceAvailability());
        assertEquals(List.of(12L), dockerReq.getEvidenceItemIds());
        assertTrue(dockerReq.getEvidenceNodeIds().isEmpty());
        assertTrue(analysis.getEvidenceQuestions().stream().noneMatch(item -> "Docker".equals(item.getTopic())));
        assertTrue(analysis.getScoreExplanation().getNextActions().stream()
                .noneMatch(action -> action.contains("Docker") && action.contains("Have you used")));
    }

    @Test
    void userRejectedKubernetesDoesNotRaiseScore() throws Exception {
        stubOwned(docxResume(), ownedJob());
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts());
        ResumeEvidenceItem rejected = new ResumeEvidenceItem();
        rejected.setId(13L);
        rejected.setTopicKey("kubernetes");
        rejected.setAnswerStatus("CONFIRMED_NOT_USED");
        when(resumeEvidenceMapper.findByResumeAndUser(9L, 1L)).thenReturn(List.of(rejected));

        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisRequirement kubernetes = byTerm(analysis, "Kubernetes");
        assertEquals(79, analysis.getOverallScore());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, kubernetes.getStatus());
        assertEquals(EvidenceAvailability.USER_REJECTED, kubernetes.getEvidenceAvailability());
        assertTrue(analysis.getEvidenceQuestions().stream().noneMatch(item -> "Kubernetes".equals(item.getTopic())));
    }

    @Test
    void deletingAnswerAllowsQuestionToReturn() throws Exception {
        stubOwned(docxResume(), ownedJob());
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts());
        when(resumeEvidenceMapper.findByResumeAndUser(9L, 1L)).thenReturn(List.of());

        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 9L);
        assertTrue(analysis.getEvidenceQuestions().stream().anyMatch(item -> "Docker".equals(item.getTopic())));
    }

    @Test
    void analyze_yaohangCurrentJd_mvcAndInProgressEducation() throws Exception {
        Job job = ownedJob();
        job.setDescription(CURRENT_JD);
        stubOwned(docxResume(), job);
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(currentJdDrafts());

        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisRequirement mvc = byTerm(analysis, "MVC");
        TailorAnalysisRequirement cs = analysis.getRequirements().stream()
                .filter(item -> RequirementCategory.QUALIFICATION.equals(item.getCategory()))
                .filter(item -> item.getRequirement().toLowerCase().contains("computer science"))
                .findFirst()
                .orElseThrow();

        assertEquals(CoverageStatus.SUPPORTED, mvc.getStatus());
        assertEquals("PROJECT_BULLET", mvc.getEvidenceKind());
        assertTrue(mvc.getEvidenceNodeIds().contains("p-16"));
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, cs.getStatus());
        assertEquals("currently pursuing", cs.getCoverageReason());
        assertTrue(analysis.getEvidenceQuestions().stream().noneMatch(item ->
                item.getTopic() != null && item.getTopic().toLowerCase().contains("mvc")));
        assertTrue(analysis.getEvidenceQuestions().stream().noneMatch(item ->
                QuestionType.EDUCATION_CONFIRMATION.equals(item.getQuestionType())
                        || (item.getTopic() != null && item.getTopic().toLowerCase().contains("degree"))));
        assertTrue(analysis.getEvidenceQuestions().stream().anyMatch(item -> "Docker".equals(item.getTopic())));
        assertTrue(analysis.getEvidenceQuestions().stream().anyMatch(item -> "Agile".equals(item.getTopic())));
        assertEquals(67, analysis.getDimensions().getHardSkills());
        assertEquals(100, analysis.getDimensions().getResponsibilities());
        assertEquals(50, analysis.getDimensions().getQualifications());
        assertEquals(65, analysis.getDimensions().getEvidenceStrength());
        assertEquals(55, analysis.getDimensions().getAtsSearchability());
        assertEquals(71, analysis.getOverallScore());
        verify(jobRequirementExtractor, times(1)).extract(any());
    }

    @Test
    void analyze_sameJobDescriptionResumeFourTimes_reusesProfile() throws Exception {
        Job job = ownedJob();
        job.setDescription(CURRENT_JD);
        stubOwned(docxResume(), job);
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(currentJdDrafts());

        TailorAnalysisResponse first = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse second = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse third = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse fourth = service.analyze(EMAIL, 153L, 9L);

        assertEquals(List.of(first.getOverallScore(), first.getOverallScore(), first.getOverallScore()),
                List.of(second.getOverallScore(), third.getOverallScore(), fourth.getOverallScore()));
        assertEquals(71, first.getOverallScore());
        assertEquals(requirementIds(first), requirementIds(second));
        assertEquals(requirementIds(first), requirementIds(third));
        assertEquals(requirementIds(first), requirementIds(fourth));
        assertEquals(requirementStatuses(first), requirementStatuses(fourth));
        assertEquals(requirementPriorities(first), requirementPriorities(fourth));
        assertEquals(first.getDimensions(), fourth.getDimensions());
        verify(jobRequirementExtractor, times(1)).extract(any());
    }

    @Test
    void analyze_afterEvidenceBankSave_doesNotReextractOrChangeScore() throws Exception {
        stubOwned(docxResume(), ownedJob());
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts());
        when(resumeEvidenceMapper.findByResumeAndUser(9L, 1L)).thenReturn(List.of());

        TailorAnalysisResponse before = service.analyze(EMAIL, 153L, 9L);
        ResumeEvidenceItem docker = new ResumeEvidenceItem();
        docker.setId(12L);
        docker.setTopicKey("docker");
        docker.setTopicLabel("Docker");
        docker.setCategory(RequirementCategory.HARD_SKILL);
        docker.setAnswerStatus("CONFIRMED_USED");
        docker.setActionText("Containerized the Spring Boot application with Docker");
        when(resumeEvidenceMapper.findByResumeAndUser(9L, 1L)).thenReturn(List.of(docker));

        TailorAnalysisResponse after = service.analyze(EMAIL, 153L, 9L);

        verify(jobRequirementExtractor, times(1)).extract(any());
        assertEquals(requirementIds(before), requirementIds(after));
        assertEquals(before.getOverallScore(), after.getOverallScore());
        assertEquals(79, after.getOverallScore());
        assertEquals(requirementStatuses(before), requirementStatuses(after));
        assertEquals(before.getDimensions(), after.getDimensions());
        assertEquals(EvidenceAvailability.USER_CONFIRMED, byTerm(after, "Docker").getEvidenceAvailability());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, byTerm(after, "Docker").getStatus());
        assertTrue(after.getEvidenceQuestions().stream().noneMatch(item -> "Docker".equals(item.getTopic())));
    }

    @Test
    void analyze_switchResume_reusesRequirementsAndRematches() throws Exception {
        stubOwned(docxResume(), ownedJob());
        Resume other = docxResume("other.docx");
        other.setId(11L);
        other.setExtractedText("Yaohang Tang\nPython Vue CSS");
        when(resumeMapper.findById(11L)).thenReturn(other);
        when(resumeDocumentService.parseDocx(any())).thenAnswer(invocation -> {
            Path path = invocation.getArgument(0);
            if (path.getFileName().toString().contains("other")) {
                return List.of(
                        new ResumeDocumentNode("p-0", "PARAGRAPH", "Yaohang Tang"),
                        new ResumeDocumentNode("p-3", "PARAGRAPH", "TECHNICAL SKILLS"),
                        new ResumeDocumentNode("p-11", "PARAGRAPH", "Python, Vue, CSS")
                );
            }
            return ResumeRequirementMatcherTest.yaohangNodes();
        });
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts());

        TailorAnalysisResponse original = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse switched = service.analyze(EMAIL, 153L, 11L);

        verify(jobRequirementExtractor, times(1)).extract(any());
        assertEquals(requirementIds(original), requirementIds(switched));
        assertEquals(original.getRequirements().size(), switched.getRequirements().size());
        assertNotEquals(original.getOverallScore(), switched.getOverallScore());
        assertEquals(CoverageStatus.SUPPORTED, byTerm(original, "Java").getStatus());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, byTerm(switched, "Java").getStatus());
    }

    @Test
    void analyze_jobDescriptionChange_rebuildsProfile() throws Exception {
        Job job = ownedJob();
        stubOwned(docxResume(), job);
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts(), currentJdDrafts());

        TailorAnalysisResponse first = service.analyze(EMAIL, 153L, 9L);
        job.setDescription(CURRENT_JD);
        TailorAnalysisResponse rebuilt = service.analyze(EMAIL, 153L, 9L);

        verify(jobRequirementExtractor, times(2)).extract(any());
        assertEquals(1, profileMapper.size());
        assertTrue(rebuilt.getRequirements().stream().anyMatch(item ->
                item.getRequirement().toLowerCase().contains("mvc")
                        || item.getExactTerms().stream().anyMatch(term -> term.equalsIgnoreCase("MVC architecture"))));
        assertNotEquals(requirementIds(first), requirementIds(rebuilt));
        assertEquals(JobDescriptionHash.sha256(CURRENT_JD),
                profileMapper.findByUserAndJob(1L, 153L).getDescriptionSha256());
    }

    @Test
    void analyze_schemaVersionChange_rebuildsProfile() throws Exception {
        stubOwned(docxResume(), ownedJob());
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts());
        JobRequirementProfile stale = new JobRequirementProfile();
        stale.setUserId(1L);
        stale.setJobId(153L);
        stale.setDescriptionSha256(JobDescriptionHash.sha256(JUNIOR_JAVA_JD));
        stale.setSchemaVersion(0);
        stale.setExtractionModel("gpt-4o");
        stale.setRequirementsJson("[]");
        profileMapper.upsert(stale);

        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 9L);

        verify(jobRequirementExtractor, times(1)).extract(any());
        assertEquals(RequirementProfileIds.SCHEMA_VERSION,
                profileMapper.findByUserAndJob(1L, 153L).getSchemaVersion());
        assertTrue(analysis.getRequirements().size() > 0);
    }

    @Test
    void analyze_corruptProfileJson_rebuildsSafely() throws Exception {
        stubOwned(docxResume(), ownedJob());
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts());
        JobRequirementProfile corrupt = new JobRequirementProfile();
        corrupt.setUserId(1L);
        corrupt.setJobId(153L);
        corrupt.setDescriptionSha256(JobDescriptionHash.sha256(JUNIOR_JAVA_JD));
        corrupt.setSchemaVersion(RequirementProfileIds.SCHEMA_VERSION);
        corrupt.setExtractionModel("gpt-4o");
        corrupt.setRequirementsJson("{not-json");
        profileMapper.upsert(corrupt);

        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 9L);

        verify(jobRequirementExtractor, times(1)).extract(any());
        assertEquals(79, analysis.getOverallScore());
        assertTrue(profileMapper.findByUserAndJob(1L, 153L).getRequirementsJson().startsWith("["));
    }

    @Test
    void refreshProfile_forcesExtractorAndOverwrites() throws Exception {
        stubOwned(docxResume(), ownedJob());
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts(), refreshVariantDrafts());

        TailorAnalysisResponse first = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse refreshed = service.refreshProfile(EMAIL, 153L, 9L);
        TailorAnalysisResponse after = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse after2 = service.analyze(EMAIL, 153L, 9L);
        TailorAnalysisResponse after3 = service.analyze(EMAIL, 153L, 9L);

        verify(jobRequirementExtractor, times(2)).extract(any());
        assertNotEquals(requirementIds(first), requirementIds(refreshed));
        assertEquals(requirementIds(refreshed), requirementIds(after));
        assertEquals(refreshed.getOverallScore(), after.getOverallScore());
        assertEquals(after.getOverallScore(), after2.getOverallScore());
        assertEquals(after.getOverallScore(), after3.getOverallScore());
        assertEquals(requirementStatuses(after), requirementStatuses(after3));
    }

    @Test
    void refreshProfile_otherUsersJob_is404() {
        User user = user(1L);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job foreign = ownedJob();
        foreign.setUserId(2L);
        when(jobMapper.findById(153L)).thenReturn(foreign);

        assertThrows(JobNotFoundException.class, () -> service.refreshProfile(EMAIL, 153L, 9L));
    }

    @Test
    void analyze_duplicateKeyDuringUpsert_doesNotThrow() throws Exception {
        DuplicateOnceProfileMapper racing = new DuplicateOnceProfileMapper();
        service = newService(racing);
        stubOwned(docxResume(), ownedJob());
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts());

        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 9L);

        assertEquals(79, analysis.getOverallScore());
        assertEquals(1, racing.delegate.size());
        assertEquals(2, racing.upsertCalls.get());
    }

    @Test
    void concurrentFirstAnalyze_storesOneProfile() throws Exception {
        stubOwned(docxResume(), ownedJob());
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(jobRequirementExtractor.extract(any())).thenReturn(juniorJavaDrafts());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<TailorAnalysisResponse> first = pool.submit(() -> service.analyze(EMAIL, 153L, 9L));
            Future<TailorAnalysisResponse> second = pool.submit(() -> service.analyze(EMAIL, 153L, 9L));
            TailorAnalysisResponse a = first.get();
            TailorAnalysisResponse b = second.get();
            assertEquals(a.getOverallScore(), b.getOverallScore());
            assertEquals(requirementIds(a), requirementIds(b));
            assertEquals(1, profileMapper.size());
            verify(jobRequirementExtractor, times(1)).extract(any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sanitizeDrafts_dropsTechnologiesNotInTheJd() {
        JobRequirementDraft invented = draft(
                "Terraform operators",
                RequirementCategory.HARD_SKILL,
                RequirementPriority.MUST_HAVE,
                List.of("Terraform")
        );
        JobRequirementDraft java = draft(
                "Java and Spring Boot",
                RequirementCategory.HARD_SKILL,
                RequirementPriority.MUST_HAVE,
                List.of("Java", "Spring Boot", "Terraform")
        );

        List<JobRequirementDraft> cleaned = service.sanitizeDrafts(
                List.of(invented, java),
                JUNIOR_JAVA_JD
        );

        assertEquals(1, cleaned.size());
        assertEquals("Java and Spring Boot", cleaned.get(0).getRequirement());
        assertEquals(List.of("Java", "Spring Boot"), cleaned.get(0).getExactTerms());
    }

    private void stubOwned(Resume resume, Job job) {
        stubUserAndJob();
        when(resumeMapper.findById(9L)).thenReturn(resume);
        when(jobMapper.findById(153L)).thenReturn(job);
    }

    private void stubUserAndJob() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L));
        when(jobMapper.findById(153L)).thenReturn(ownedJob());
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        user.setEmail(EMAIL);
        return user;
    }

    private Job ownedJob() {
        Job job = new Job();
        job.setId(153L);
        job.setUserId(1L);
        job.setTitle("Junior Java Backend Developer");
        job.setDescription(JUNIOR_JAVA_JD);
        return job;
    }

    private Resume docxResume() {
        return docxResume("resume.docx");
    }

    private Resume docxResume(String fileName) {
        try {
            Path file = tempDir.resolve(fileName);
            if (!Files.exists(file)) {
                Files.writeString(file, "placeholder");
            }
            Resume resume = new Resume();
            resume.setId(9L);
            resume.setUserId(1L);
            resume.setFileType("docx");
            resume.setFilePath(file.toString());
            resume.setOrigin("UPLOAD");
            resume.setExtractedText("Yaohang Tang\nJava Spring Boot REST Maven");
            return resume;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<JobRequirementDraft> currentJdDrafts() {
        return List.of(
                draft("Java and Spring Boot", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Java", "Spring Boot")),
                draft("REST APIs", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("REST APIs")),
                draft("Maven", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Maven")),
                draft("Implement features using MVC architecture", RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE, List.of("MVC architecture")),
                draft("Pursuing or completed a degree in Computer Science or related field",
                        RequirementCategory.QUALIFICATION, RequirementPriority.PREFERRED,
                        List.of("degree", "Computer Science")),
                draft("Design and implement backend services", RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE, List.of("Design", "implement")),
                draft("Docker and Kubernetes", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        List.of("Docker", "Kubernetes")),
                draft("Agile", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        List.of("Agile"))
        );
    }

    private List<JobRequirementDraft> juniorJavaDrafts() {
        return List.of(
                draft("Java and Spring Boot", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Java", "Spring Boot")),
                draft("REST APIs", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("REST")),
                draft("Maven", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Maven")),
                draft("Design and implement backend services", RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE, List.of("Design", "implement")),
                draft("Bachelor degree", RequirementCategory.QUALIFICATION, RequirementPriority.PREFERRED,
                        List.of("Bachelor")),
                draft("Docker and Kubernetes", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        List.of("Docker", "Kubernetes"))
        );
    }

    private List<JobRequirementDraft> refreshVariantDrafts() {
        return List.of(
                draft("Java and Spring Boot", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Java", "Spring Boot")),
                draft("REST API", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("REST API")),
                draft("Maven", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Maven")),
                draft("Design and implement backend services", RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE, List.of("Design", "implement")),
                draft("Docker and Kubernetes", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Docker", "Kubernetes"))
        );
    }

    private JobRequirementDraft draft(String requirement, String category, String priority, List<String> terms) {
        JobRequirementDraft draft = new JobRequirementDraft();
        draft.setRequirement(requirement);
        draft.setCategory(category);
        draft.setPriority(priority);
        draft.setExactTerms(terms);
        return draft;
    }

    private TailorAnalysisRequirement byTerm(TailorAnalysisResponse response, String term) {
        return response.getRequirements().stream()
                .filter(item -> item.getExactTerms().stream()
                        .anyMatch(value -> value.equalsIgnoreCase(term))
                        || item.getRequirement().contains(term))
                .findFirst()
                .orElseThrow();
    }

    private List<String> requirementIds(TailorAnalysisResponse response) {
        return response.getRequirements().stream().map(TailorAnalysisRequirement::getId).toList();
    }

    private List<String> requirementStatuses(TailorAnalysisResponse response) {
        return response.getRequirements().stream()
                .map(item -> item.getId() + "|" + item.getStatus())
                .toList();
    }

    private List<String> requirementPriorities(TailorAnalysisResponse response) {
        return response.getRequirements().stream()
                .map(item -> item.getId() + "|" + item.getPriority())
                .toList();
    }

    static final class InMemoryJobRequirementProfileMapper implements JobRequirementProfileMapper {

        private final Map<String, JobRequirementProfile> store = new ConcurrentHashMap<>();
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public JobRequirementProfile findByUserAndJob(Long userId, Long jobId) {
            return store.get(key(userId, jobId));
        }

        @Override
        public int upsert(JobRequirementProfile profile) {
            String key = key(profile.getUserId(), profile.getJobId());
            JobRequirementProfile existing = store.get(key);
            LocalDateTime now = LocalDateTime.now();
            if (existing == null) {
                profile.setId((long) nextId.getAndIncrement());
                profile.setCreatedAt(now);
            } else {
                profile.setId(existing.getId());
                profile.setCreatedAt(existing.getCreatedAt());
            }
            profile.setUpdatedAt(now);
            store.put(key, profile);
            return 1;
        }

        int size() {
            return store.size();
        }

        private static String key(Long userId, Long jobId) {
            return userId + ":" + jobId;
        }
    }

    static final class DuplicateOnceProfileMapper implements JobRequirementProfileMapper {

        private final InMemoryJobRequirementProfileMapper delegate = new InMemoryJobRequirementProfileMapper();
        private final AtomicInteger upsertCalls = new AtomicInteger();

        @Override
        public JobRequirementProfile findByUserAndJob(Long userId, Long jobId) {
            return delegate.findByUserAndJob(userId, jobId);
        }

        @Override
        public int upsert(JobRequirementProfile profile) {
            if (upsertCalls.incrementAndGet() == 1) {
                throw new DuplicateKeyException("Duplicate entry");
            }
            return delegate.upsert(profile);
        }
    }
}
