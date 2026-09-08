package aijob.service.impl;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceKind;
import aijob.dto.JobRequirementDraft;
import aijob.dto.PdfLayout;
import aijob.dto.QuestionType;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeTextBlock;
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
import aijob.util.GeneratedResumePdfFixture;
import aijob.util.PdfLayoutExtractor;
import aijob.util.QualificationMatcher;
import aijob.util.ResumeAnalysisInput;
import aijob.util.ResumeAnalysisInputFactory;
import aijob.util.ResumeTextExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TailorAnalysisS1ATest {

    private static final String EMAIL = "user@example.com";
    private static final String S1A_JD = """
            Junior Java Backend Developer

            Required qualifications:
            Currently pursuing or recently completed a degree in Computer Science

            Responsibilities:
            Write thoroughly tested and well-documented code.
            Successfully deployed onto AWS.
            You will optimize program functionalities.
            You will debug production issues.
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
    private JobRequirementExtractor jobRequirementExtractor;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResumeDocumentService resumeDocumentService = new ResumeDocumentServiceImpl();
    private final ResumeTemplateDocxServiceImpl templateWriter = new ResumeTemplateDocxServiceImpl();
    private final PdfLayoutExtractor layoutExtractor = new PdfLayoutExtractor();
    private final ResumeTextExtractor textExtractor = new ResumeTextExtractor();
    private TailorAnalysisServiceImplTest.InMemoryJobRequirementProfileMapper profileMapper;
    private ResumeAnalysisInputFactory factory;
    private TailorAnalysisServiceImpl service;
    private Resume parent;
    private Resume child;
    private Path childPath;

    @BeforeEach
    void setUp() throws Exception {
        profileMapper = new TailorAnalysisServiceImplTest.InMemoryJobRequirementProfileMapper();
        factory = new ResumeAnalysisInputFactory(resumeMapper, resumeDocumentService, objectMapper);
        service = new TailorAnalysisServiceImpl(
                userMapper,
                jobMapper,
                resumeMapper,
                resumeEvidenceMapper,
                resumeDocumentService,
                jobRequirementExtractor,
                profileMapper,
                objectMapper,
                factory,
                "gpt-4o"
        );
        fixtureResumes();
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        when(jobMapper.findById(153L)).thenReturn(ownedJob());
        lenient().when(resumeMapper.findById(21L)).thenReturn(parent);
        lenient().when(resumeMapper.findById(22L)).thenReturn(child);
        lenient().when(resumeEvidenceMapper.findByResumeAndUser(21L, 1L)).thenReturn(List.of());
        lenient().when(jobRequirementExtractor.extract(any())).thenReturn(s1aDrafts());
    }

    @Test
    void selectedPdfParent_withLayoutSourceBlockIds_computerScienceIsPartialEducation() {
        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 21L);
        TailorAnalysisRequirement cs = computerScience(analysis);

        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, cs.getStatus());
        assertEquals(EvidenceKind.EDUCATION, cs.getEvidenceKind());
        assertEquals(QualificationMatcher.CURRENTLY_PURSUING, cs.getCoverageReason());
        assertEquals(List.of("edu-structure-0"), cs.getEvidenceNodeIds());
        assertTrue(cs.getEvidenceQuotes().stream().anyMatch(quote -> quote.contains("Bachelor of Computer Science")));
        assertTrue(cs.getEvidenceQuotes().stream().anyMatch(quote -> quote.contains("Present")));
        assertEquals(50, analysis.getDimensions().getQualifications());
        assertFalse(cs.getEvidenceNodeIds().stream().anyMatch(id -> id.startsWith("b-p")));
        assertTrue(parentSourceBlockIsLayout());
    }

    @Test
    void selectedCanonicalChild_matchesParentAnalysis() {
        TailorAnalysisResponse fromParent = service.analyze(EMAIL, 153L, 21L);
        TailorAnalysisResponse fromChild = service.analyze(EMAIL, 153L, 22L);

        assertEquals(core(fromParent), core(fromChild));
        assertEquals("edu-structure-0", computerScience(fromChild).getEvidenceNodeIds().get(0));
        assertTrue(child.getStructuredJson() == null || child.getStructuredJson().isBlank());
    }

    @Test
    void parentStructuredEducation_childStructuredJsonEmpty_stillFindsDegree() {
        child.setStructuredJson(null);
        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 22L);
        TailorAnalysisRequirement cs = computerScience(analysis);

        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, cs.getStatus());
        assertEquals(EvidenceKind.EDUCATION, cs.getEvidenceKind());
        assertEquals(List.of("edu-structure-0"), cs.getEvidenceNodeIds());
    }

    @Test
    void pdfParentWithoutChild_usesLayoutNodesAndStillVerifiesEducation() {
        parent.setCanonicalResumeId(null);
        ResumeAnalysisInput input = factory.resolve(parent, 1L);
        assertTrue(input.matchingNodes().stream().allMatch(node -> node.getNodeId().startsWith("b-p")));
        assertTrue(input.matchingNodes().stream().anyMatch(node ->
                node.getText() != null && node.getText().toLowerCase().contains("computer science")));

        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 21L);
        TailorAnalysisRequirement cs = computerScience(analysis);
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, cs.getStatus());
        assertEquals(EvidenceKind.EDUCATION, cs.getEvidenceKind());
        assertEquals(List.of("edu-structure-0"), cs.getEvidenceNodeIds());
    }

    @Test
    void nativeDocx_readsDegreeFromEducationSection() throws Exception {
        Path nativePath = tempDir.resolve("native.docx");
        templateWriter.write(canonicalStructure(List.of()), nativePath);
        Resume nativeResume = new Resume();
        nativeResume.setId(31L);
        nativeResume.setUserId(1L);
        nativeResume.setFileType("docx");
        nativeResume.setOrigin("UPLOAD");
        nativeResume.setFilePath(nativePath.toString());
        nativeResume.setExtractedText(textExtractor.extract(nativePath, "docx"));
        nativeResume.setStructuredJson(null);
        when(resumeMapper.findById(31L)).thenReturn(nativeResume);
        when(resumeEvidenceMapper.findByResumeAndUser(31L, 1L)).thenReturn(List.of());

        List<ResumeDocumentNode> nodes = resumeDocumentService.parseDocx(nativePath);
        assertTrue(nodes.stream().anyMatch(node -> "EDUCATION".equalsIgnoreCase(node.getText())));

        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 31L);
        TailorAnalysisRequirement cs = computerScience(analysis);
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, cs.getStatus());
        assertEquals(EvidenceKind.EDUCATION, cs.getEvidenceKind());
        assertEquals(QualificationMatcher.CURRENTLY_PURSUING, cs.getCoverageReason());
        assertFalse(cs.getEvidenceNodeIds().isEmpty());
        assertTrue(cs.getEvidenceQuotes().stream().anyMatch(quote ->
                quote.toLowerCase().contains("computer science") || quote.contains("Present")));
    }

    @Test
    void realSentences_testDocumentDeploySupported_optimizeAtMostPartial_debugMissing() {
        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 21L);
        assertEquals(CoverageStatus.SUPPORTED, byTerm(analysis, "test").getStatus());
        assertEquals(CoverageStatus.SUPPORTED, byTerm(analysis, "document").getStatus());
        assertEquals(CoverageStatus.SUPPORTED, byTerm(analysis, "deploy").getStatus());
        assertNotEquals(CoverageStatus.SUPPORTED, byTerm(analysis, "optimize").getStatus());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, byTerm(analysis, "debug").getStatus());
    }

    @Test
    void computerSciencePresent_doesNotAskEducationConfirmation() {
        TailorAnalysisResponse analysis = service.analyze(EMAIL, 153L, 21L);
        assertTrue(analysis.getEvidenceQuestions().stream().noneMatch(item ->
                QuestionType.EDUCATION_CONFIRMATION.equals(item.getQuestionType())));
    }

    @Test
    void parentAndChildEachAnalyzedFourTimes_areStableAndExtractorRunsOnce() {
        TailorAnalysisResponse firstParent = service.analyze(EMAIL, 153L, 21L);
        TailorAnalysisResponse secondParent = service.analyze(EMAIL, 153L, 21L);
        TailorAnalysisResponse thirdParent = service.analyze(EMAIL, 153L, 21L);
        TailorAnalysisResponse fourthParent = service.analyze(EMAIL, 153L, 21L);
        TailorAnalysisResponse firstChild = service.analyze(EMAIL, 153L, 22L);
        TailorAnalysisResponse secondChild = service.analyze(EMAIL, 153L, 22L);
        TailorAnalysisResponse thirdChild = service.analyze(EMAIL, 153L, 22L);
        TailorAnalysisResponse fourthChild = service.analyze(EMAIL, 153L, 22L);

        List<String> expectedCore = core(firstParent);
        assertEquals(expectedCore, core(secondParent));
        assertEquals(expectedCore, core(thirdParent));
        assertEquals(expectedCore, core(fourthParent));
        assertEquals(expectedCore, core(firstChild));
        assertEquals(expectedCore, core(secondChild));
        assertEquals(expectedCore, core(thirdChild));
        assertEquals(expectedCore, core(fourthChild));
        verify(jobRequirementExtractor, times(1)).extract(any());
    }

    private void fixtureResumes() throws Exception {
        Path sourcePdf = GeneratedResumePdfFixture.pdf();
        Path parentPdf = tempDir.resolve("generated-resume.pdf");
        Files.copy(sourcePdf, parentPdf);
        PdfLayout layout = layoutExtractor.extract(sourcePdf);
        ResumeTextBlock csBlock = layout.getBlocks().stream()
                .filter(block -> block.getText() != null
                        && block.getText().toLowerCase().contains("computer science"))
                .findFirst()
                .orElseThrow();
        ResumeStructure parentStructure = canonicalStructure(List.of(csBlock.getId()));
        parent = new Resume();
        parent.setId(21L);
        parent.setUserId(1L);
        parent.setFileType("pdf");
        parent.setOrigin("UPLOAD");
        parent.setFilePath(parentPdf.toString());
        parent.setExtractedText(textExtractor.extract(sourcePdf, "pdf"));
        parent.setLayoutJson(objectMapper.writeValueAsString(layout));
        parent.setStructuredJson(objectMapper.writeValueAsString(parentStructure));
        parent.setCanonicalResumeId(22L);

        childPath = tempDir.resolve("canonical.docx");
        templateWriter.write(canonicalStructure(List.of()), childPath);
        child = new Resume();
        child.setId(22L);
        child.setUserId(1L);
        child.setFileType("docx");
        child.setOrigin("TEMPLATE");
        child.setParentResumeId(21L);
        child.setReviewStatus("READY");
        child.setFilePath(childPath.toString());
        child.setExtractedText(textExtractor.extract(childPath, "docx"));
        child.setStructuredJson(null);
    }

    private boolean parentSourceBlockIsLayout() {
        try {
            ResumeStructure structure = objectMapper.readValue(parent.getStructuredJson(), ResumeStructure.class);
            return structure.getEducation().get(0).getSourceBlockIds().stream()
                    .anyMatch(id -> id != null && id.startsWith("b-p"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ResumeStructure canonicalStructure(List<String> educationSourceBlockIds) {
        ResumeStructure structure = ResumeStructure.empty();
        structure.getContact().setFullName("Alex Morgan");
        structure.getContact().setEmail("alex.morgan@example.com");
        structure.getSummaryBullets().add(bullet(
                "Exceptional analytical and problem-solving skills with continuous thrive in optimizing program functionalities"
        ));
        ResumeStructure.ResumeProject project = new ResumeStructure.ResumeProject();
        project.normalize();
        project.setName("SmartPC Builder");
        project.getBullets().add(
                "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality"
        );
        project.getBullets().add("Successfully deployed onto AWS");
        structure.getProjects().add(project);
        ResumeStructure.ResumeEducation education = new ResumeStructure.ResumeEducation();
        education.setSchool("Example University");
        education.setDegree("Bachelor of Computer Science");
        education.setDates("Sept 2023 – Present");
        education.setSourceBlockIds(educationSourceBlockIds);
        structure.getEducation().add(education);
        return structure;
    }

    private static ResumeStructure.ResumeBullet bullet(String text) {
        ResumeStructure.ResumeBullet item = new ResumeStructure.ResumeBullet();
        item.setText(text);
        return item;
    }

    private Job ownedJob() {
        Job job = new Job();
        job.setId(153L);
        job.setUserId(1L);
        job.setTitle("Junior Java Backend Developer");
        job.setDescription(S1A_JD);
        return job;
    }

    private static List<JobRequirementDraft> s1aDrafts() {
        return List.of(
                draft(
                        "Currently pursuing or recently completed a degree in Computer Science",
                        RequirementCategory.QUALIFICATION,
                        RequirementPriority.MUST_HAVE,
                        List.of("Computer Science")
                ),
                draft("test", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, List.of("tested")),
                draft("document", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, List.of("documented")),
                draft("deploy", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, List.of("deployed")),
                draft("optimize", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, List.of("optimize")),
                draft("debug", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, List.of("debug"))
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

    private static TailorAnalysisRequirement computerScience(TailorAnalysisResponse response) {
        return response.getRequirements().stream()
                .filter(item -> RequirementCategory.QUALIFICATION.equals(item.getCategory()))
                .filter(item -> item.getRequirement().toLowerCase().contains("computer science"))
                .findFirst()
                .orElseThrow();
    }

    private static TailorAnalysisRequirement byTerm(TailorAnalysisResponse response, String term) {
        return response.getRequirements().stream()
                .filter(item -> item.getRequirement().equalsIgnoreCase(term)
                        || item.getExactTerms().stream().anyMatch(value -> value.equalsIgnoreCase(term)))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> core(TailorAnalysisResponse response) {
        return List.of(
                response.getRequirements().stream().map(TailorAnalysisRequirement::getId).toList().toString(),
                response.getRequirements().stream()
                        .map(item -> item.getId() + "|" + item.getStatus())
                        .toList()
                        .toString(),
                String.valueOf(response.getDimensions()),
                String.valueOf(response.getOverallScore())
        );
    }

}
