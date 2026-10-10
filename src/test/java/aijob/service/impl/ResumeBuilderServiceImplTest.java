package aijob.service.impl;

import aijob.config.StoragePathResolver;
import aijob.dto.ResumeResponse;
import aijob.dto.builder.CreateResumeBuilderDraftRequest;
import aijob.dto.builder.GenerateResumeBuilderDraftRequest;
import aijob.dto.builder.NormalizedResume;
import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftResponse;
import aijob.dto.builder.ResumeBuilderSectionIds;
import aijob.dto.builder.UpdateResumeBuilderDraftRequest;
import aijob.entity.Resume;
import aijob.entity.ResumeBuilderDraft;
import aijob.entity.ResumeBuilderGenerationRequest;
import aijob.entity.User;
import aijob.enums.ResumeOrigin;
import aijob.exception.InvalidResumeFileException;
import aijob.exception.ResumeBuilderDraftConflictException;
import aijob.exception.ResumeBuilderDraftNotFoundException;
import aijob.mapper.ResumeBuilderDraftMapper;
import aijob.mapper.ResumeBuilderGenerationRequestMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.util.CareerMaterialsGenerator;
import aijob.util.ResumeBuilderDraftValidator;
import aijob.util.ResumeBuilderNormalizer;
import aijob.util.ResumeBuilderProfessionalLinks;
import aijob.util.ResumeTextExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fixtures: Alex Morgan, Example Company -- entirely fictional. Note this class's
 * constructor dependencies do NOT include {@code AiResumeService} or any OpenAI-calling
 * bean at all -- generation and career materials are structurally incapable of calling
 * OpenAI, not merely configured not to (see CareerMaterialsGeneratorTest and
 * ResumeBuilderNormalizerTest for the same point at their own layers).
 */
@ExtendWith(MockitoExtension.class)
class ResumeBuilderServiceImplTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private ResumeBuilderDraftMapper draftMapper;
    @Mock
    private ResumeBuilderGenerationRequestMapper generationRequestMapper;
    @Mock
    private ResumeMapper resumeMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ResumeTextExtractor resumeTextExtractor;
    @Spy
    private ResumeBuilderDraftValidator validator = new ResumeBuilderDraftValidator();
    @Spy
    private ResumeBuilderNormalizer normalizer = new ResumeBuilderNormalizer();
    @Spy
    private ResumeBuilderDocxServiceImpl resumeBuilderDocxService = new ResumeBuilderDocxServiceImpl();
    @Spy
    private CareerMaterialsGenerator careerMaterialsGenerator = new CareerMaterialsGenerator();
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private StoragePathResolver storagePathResolver;
    private ResumeBuilderServiceImpl service;
    private User owner;
    private final AtomicLong draftIds = new AtomicLong(100);
    private ResumeBuilderDraft lastInsertedDraft;
    private Resume lastInsertedResume;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(2L);
        owner.setEmail(EMAIL);
        storagePathResolver = StoragePathResolver.forRoot(tempDir.resolve("uploads"));
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);

        service = new ResumeBuilderServiceImpl(
                draftMapper,
                generationRequestMapper,
                resumeMapper,
                userMapper,
                validator,
                normalizer,
                resumeBuilderDocxService,
                careerMaterialsGenerator,
                resumeTextExtractor,
                storagePathResolver,
                objectMapper);
    }

    private void stubDraftInsert() {
        when(draftMapper.insert(any(ResumeBuilderDraft.class))).thenAnswer(inv -> {
            ResumeBuilderDraft draft = inv.getArgument(0);
            draft.setId(draftIds.incrementAndGet());
            draft.setVersion(1L);
            lastInsertedDraft = draft;
            return 1;
        });
        when(draftMapper.findByIdAndUserId(anyLong(), eq(owner.getId())))
                .thenAnswer(inv -> lastInsertedDraft);
    }

    private ResumeBuilderDraft filledDraft(String draftJson) {
        ResumeBuilderDraft draft = new ResumeBuilderDraft();
        draft.setId(1L);
        draft.setUserId(owner.getId());
        draft.setTitle("My Resume");
        draft.setTemplateType("GENERIC_ATS");
        draft.setLocale("en");
        draft.setSchemaVersion(1);
        draft.setDraftJson(draftJson);
        draft.setVersion(1L);
        return draft;
    }

    private String contentJsonWithName(String name) {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName(name);
        content.normalize();
        return objectMapper.valueToTree(content).toString();
    }

    // --- create / update -------------------------------------------------

    @Test
    void createDraftStartsCompletelyBlankNeverPrefilledWithAnyRealPersonsData() {
        stubDraftInsert();
        CreateResumeBuilderDraftRequest request =
                new CreateResumeBuilderDraftRequest("Untitled resume", "GENERIC_ATS", "en");

        ResumeBuilderDraftResponse response = service.createDraft(EMAIL, request);

        assertEquals("", response.getContent().getBasicInfo().getFullName());
        assertTrue(response.getContent().getExperience().isEmpty());
        assertTrue(response.getContent().getEducation().isEmpty());
        assertTrue(response.getContent().getProjects().isEmpty());
    }

    @Test
    void createDraftGenericAtsSuggestsLinkedInOnlyAndDoesNotSeedGithub() {
        stubDraftInsert();
        ResumeBuilderDraftResponse response = service.createDraft(
                EMAIL, new CreateResumeBuilderDraftRequest("Untitled resume", "GENERIC_ATS", "en"));

        var links = response.getContent().getBasicInfo().getProfessionalLinks();
        assertEquals(1, links.size());
        assertEquals(ResumeBuilderProfessionalLinks.LINKEDIN, links.get(0).getType());
        assertEquals("", links.get(0).getUrl());
        assertFalse(links.stream().anyMatch(link -> ResumeBuilderProfessionalLinks.GITHUB.equals(link.getType())));
        assertEquals(Boolean.TRUE, response.getContent().getSectionEnabled().get(ResumeBuilderSectionIds.SKILLS));
        assertEquals(Boolean.FALSE, response.getContent().getSectionEnabled().get(ResumeBuilderSectionIds.PROJECTS));
    }

    @Test
    void createDraftTechnicalSuggestsLinkedInGithubAndPortfolioAllOptional() {
        stubDraftInsert();
        ResumeBuilderDraftResponse response = service.createDraft(
                EMAIL, new CreateResumeBuilderDraftRequest("Untitled resume", "TECHNICAL", "en"));

        var types = response.getContent().getBasicInfo().getProfessionalLinks().stream()
                .map(aijob.dto.builder.ResumeBuilderDraftContent.ProfessionalLink::getType)
                .toList();
        assertEquals(List.of(
                ResumeBuilderProfessionalLinks.LINKEDIN,
                ResumeBuilderProfessionalLinks.GITHUB,
                ResumeBuilderProfessionalLinks.PORTFOLIO), types);
        assertTrue(response.getContent().getBasicInfo().getProfessionalLinks().stream()
                .allMatch(link -> link.getUrl() == null || link.getUrl().isBlank()));
        assertEquals(Boolean.TRUE, response.getContent().getSectionEnabled().get(ResumeBuilderSectionIds.PROJECTS));
    }

    @Test
    void updatingWithAStaleVersionIsRejectedAsAConflictNotSilentlyOverwritten() {
        ResumeBuilderDraft draft = filledDraft(contentJsonWithName("Alex Morgan"));
        when(draftMapper.findByIdAndUserId(1L, owner.getId())).thenReturn(draft);
        when(draftMapper.updateWithVersion(eq(1L), eq(owner.getId()), any(), any(), any(), any(), any(), any(), eq(1L)))
                .thenReturn(0); // 0 rows matched: version no longer current

        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        UpdateResumeBuilderDraftRequest request = new UpdateResumeBuilderDraftRequest(
                "My Resume", "GENERIC_ATS", null, "en", content, 1L);

        assertThrows(ResumeBuilderDraftConflictException.class, () -> service.updateDraft(EMAIL, 1L, request));
    }

    @Test
    void aDraftBelongingToNoOneOr404sBeforeAnyConflictCheck() {
        when(draftMapper.findByIdAndUserId(1L, owner.getId())).thenReturn(null);
        UpdateResumeBuilderDraftRequest request = new UpdateResumeBuilderDraftRequest(
                "T", "GENERIC_ATS", null, "en", ResumeBuilderDraftContent.blank(), 1L);

        assertThrows(ResumeBuilderDraftNotFoundException.class, () -> service.updateDraft(EMAIL, 1L, request));
    }

    @Test
    void previewUsesDraftLevelTargetRoleWhenJobTargetTitleIsBlank() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        content.getBasicInfo().setEmail("alex.morgan@example.com");
        content.normalize();
        ResumeBuilderDraft draft = filledDraft(objectMapper.valueToTree(content).toString());
        draft.setTargetRole("Junior Software Developer");
        when(draftMapper.findByIdAndUserId(1L, owner.getId())).thenReturn(draft);

        NormalizedResume preview = service.previewDraft(EMAIL, 1L);

        assertEquals("Junior Software Developer", preview.getHeader().getTargetTitle());
        assertTrue(preview.getMissingKeyFacts().stream().noneMatch(fact -> fact.contains("target")));
    }

    @Test
    void updateDraftCopiesTargetRoleIntoJobTargetWhenTargetTitleIsBlank() {
        ResumeBuilderDraft draft = filledDraft(contentJsonWithName("Alex Morgan"));
        when(draftMapper.findByIdAndUserId(1L, owner.getId())).thenReturn(draft);
        when(draftMapper.updateWithVersion(eq(1L), eq(owner.getId()), any(), any(), any(), any(), any(), any(), eq(1L)))
                .thenAnswer(inv -> {
                    draft.setTargetRole(inv.getArgument(4));
                    draft.setVersion(2L);
                    return 1;
                });

        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        UpdateResumeBuilderDraftRequest request = new UpdateResumeBuilderDraftRequest(
                "My Resume", "GENERIC_ATS", "Junior Software Developer", "en", content, 1L);

        ResumeBuilderDraftResponse response = service.updateDraft(EMAIL, 1L, request);

        assertEquals("Junior Software Developer", response.getContent().getJobTarget().getTargetTitle());
        assertEquals("Junior Software Developer", response.getTargetRole());
    }

    // --- generate ---------------------------------------------------------

    @Test
    void generateWritesADocxAndSavesAGeneratedOriginResumeVisibleInMyResumes() throws Exception {
        ResumeBuilderDraft draft = filledDraft(contentJsonWithName("Alex Morgan"));
        when(draftMapper.findByIdAndUserId(1L, owner.getId())).thenReturn(draft);
        when(generationRequestMapper.findByUserIdAndKey(owner.getId(), "key-1")).thenReturn(null);
        when(resumeTextExtractor.extract(any(Path.class), eq("docx"))).thenReturn("Alex Morgan");
        stubResumeInsert();

        ResumeResponse response = service.generateFromDraft(
                EMAIL, 1L, new GenerateResumeBuilderDraftRequest("key-1"));

        assertEquals(ResumeOrigin.GENERATED.name(), response.getOrigin());
        assertTrue(Files.exists(Path.of(lastInsertedResume.getFilePath())));
        verify(generationRequestMapper).insert(any(ResumeBuilderGenerationRequest.class));
    }

    @Test
    void retryingGenerateWithTheSameIdempotencyKeyReturnsTheSameResumeAndNeverInsertsAgain() throws Exception {
        ResumeBuilderDraft draft = filledDraft(contentJsonWithName("Alex Morgan"));
        when(draftMapper.findByIdAndUserId(1L, owner.getId())).thenReturn(draft);

        Resume existing = new Resume();
        existing.setId(55L);
        existing.setUserId(owner.getId());
        existing.setOrigin(ResumeOrigin.GENERATED.name());
        ResumeBuilderGenerationRequest existingClaim = new ResumeBuilderGenerationRequest();
        existingClaim.setId(9L);
        existingClaim.setUserId(owner.getId());
        existingClaim.setResumeId(55L);
        when(generationRequestMapper.findByUserIdAndKey(owner.getId(), "key-1")).thenReturn(existingClaim);
        when(resumeMapper.findById(55L)).thenReturn(existing);

        ResumeResponse response = service.generateFromDraft(
                EMAIL, 1L, new GenerateResumeBuilderDraftRequest("key-1"));

        assertEquals(55L, response.getId());
        verify(resumeMapper, never()).insert(any(Resume.class));
    }

    @Test
    void aLosingRaceOnTheIdempotencyKeyCleansUpItsOwnResumeAndFileThenReturnsTheWinner() throws Exception {
        ResumeBuilderDraft draft = filledDraft(contentJsonWithName("Alex Morgan"));
        when(draftMapper.findByIdAndUserId(1L, owner.getId())).thenReturn(draft);
        when(generationRequestMapper.findByUserIdAndKey(owner.getId(), "key-1"))
                .thenReturn(null) // first check: no claim yet
                .thenReturn(winningClaim()); // after losing the race, look up the winner
        when(resumeTextExtractor.extract(any(Path.class), eq("docx"))).thenReturn("Alex Morgan");
        stubResumeInsert();
        when(generationRequestMapper.insert(any(ResumeBuilderGenerationRequest.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        Resume winningResume = new Resume();
        winningResume.setId(999L);
        winningResume.setUserId(owner.getId());
        winningResume.setOrigin(ResumeOrigin.GENERATED.name());
        when(resumeMapper.findById(999L)).thenReturn(winningResume);

        ResumeResponse response = service.generateFromDraft(
                EMAIL, 1L, new GenerateResumeBuilderDraftRequest("key-1"));

        assertEquals(999L, response.getId());
        // Our own losing attempt's resume row and file must be cleaned up.
        verify(resumeMapper).deleteByIdAndUserId(eq(lastInsertedResume.getId()), eq(owner.getId()));
        assertTrue(Files.notExists(Path.of(lastInsertedResume.getFilePath())));
    }

    @Test
    void aDatabaseFailureAfterWritingTheFileCleansUpTheOrphanFile() {
        ResumeBuilderDraft draft = filledDraft(contentJsonWithName("Alex Morgan"));
        when(draftMapper.findByIdAndUserId(1L, owner.getId())).thenReturn(draft);
        when(generationRequestMapper.findByUserIdAndKey(owner.getId(), "key-1")).thenReturn(null);
        when(resumeTextExtractor.extract(any(Path.class), eq("docx"))).thenReturn("Alex Morgan");
        when(resumeMapper.insert(any(Resume.class))).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.generateFromDraft(
                EMAIL, 1L, new GenerateResumeBuilderDraftRequest("key-1")));

        // No way to name the exact temp file from outside, but the resumes directory
        // must not retain any file (nothing succeeded far enough to be legitimate).
        Path dir = storagePathResolver.resumeDirectory(owner.getId());
        if (Files.exists(dir)) {
            try (var stream = Files.list(dir)) {
                assertEquals(0, stream.count());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void generateRefusesADraftWithNothingAtAllToPutInTheDocument() {
        ResumeBuilderDraft draft = filledDraft(objectMapper.valueToTree(ResumeBuilderDraftContent.blank()).toString());
        when(draftMapper.findByIdAndUserId(1L, owner.getId())).thenReturn(draft);
        when(generationRequestMapper.findByUserIdAndKey(owner.getId(), "key-1")).thenReturn(null);

        assertThrows(InvalidResumeFileException.class, () -> service.generateFromDraft(
                EMAIL, 1L, new GenerateResumeBuilderDraftRequest("key-1")));
        verify(resumeMapper, never()).insert(any(Resume.class));
    }

    private ResumeBuilderGenerationRequest winningClaim() {
        ResumeBuilderGenerationRequest claim = new ResumeBuilderGenerationRequest();
        claim.setId(42L);
        claim.setUserId(owner.getId());
        claim.setResumeId(999L);
        return claim;
    }

    private void stubResumeInsert() {
        when(resumeMapper.insert(any(Resume.class))).thenAnswer(inv -> {
            Resume resume = inv.getArgument(0);
            resume.setId(200L + (lastInsertedResume == null ? 0 : 1));
            lastInsertedResume = resume;
            return 1;
        });
        org.mockito.Mockito.lenient()
                .when(resumeMapper.deleteByIdAndUserId(anyLong(), eq(owner.getId())))
                .thenReturn(1);
    }
}
