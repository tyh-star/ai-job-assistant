package aijob.service.impl;

import aijob.config.StoragePathResolver;
import aijob.dto.ResumeResponse;
import aijob.dto.builder.CareerMaterialsResponse;
import aijob.dto.builder.CreateResumeBuilderDraftRequest;
import aijob.dto.builder.GenerateResumeBuilderDraftRequest;
import aijob.dto.builder.NormalizedResume;
import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftResponse;
import aijob.dto.builder.ResumeBuilderDraftSummary;
import aijob.dto.builder.UpdateResumeBuilderDraftRequest;
import aijob.entity.Resume;
import aijob.entity.ResumeBuilderDraft;
import aijob.entity.ResumeBuilderGenerationRequest;
import aijob.entity.User;
import aijob.enums.ResumeExtractionStatus;
import aijob.enums.ResumeOrigin;
import aijob.enums.ResumeReviewStatus;
import aijob.exception.InvalidResumeFileException;
import aijob.exception.ResumeBuilderDraftConflictException;
import aijob.exception.ResumeBuilderDraftNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.ResumeBuilderDraftMapper;
import aijob.mapper.ResumeBuilderGenerationRequestMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.ResumeBuilderDocxService;
import aijob.service.ResumeBuilderService;
import aijob.util.CareerMaterialsGenerator;
import aijob.util.ResumeBuilderDraftValidator;
import aijob.util.ResumeBuilderNormalizer;
import aijob.util.ResumeBuilderTemplateLayout;
import aijob.util.ResumeTextExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class ResumeBuilderServiceImpl implements ResumeBuilderService {

    private final ResumeBuilderDraftMapper draftMapper;
    private final ResumeBuilderGenerationRequestMapper generationRequestMapper;
    private final ResumeMapper resumeMapper;
    private final UserMapper userMapper;
    private final ResumeBuilderDraftValidator validator;
    private final ResumeBuilderNormalizer normalizer;
    private final ResumeBuilderDocxService resumeBuilderDocxService;
    private final CareerMaterialsGenerator careerMaterialsGenerator;
    private final ResumeTextExtractor resumeTextExtractor;
    private final StoragePathResolver storagePathResolver;
    private final ObjectMapper objectMapper;

    public ResumeBuilderServiceImpl(
            ResumeBuilderDraftMapper draftMapper,
            ResumeBuilderGenerationRequestMapper generationRequestMapper,
            ResumeMapper resumeMapper,
            UserMapper userMapper,
            ResumeBuilderDraftValidator validator,
            ResumeBuilderNormalizer normalizer,
            ResumeBuilderDocxService resumeBuilderDocxService,
            CareerMaterialsGenerator careerMaterialsGenerator,
            ResumeTextExtractor resumeTextExtractor,
            StoragePathResolver storagePathResolver,
            ObjectMapper objectMapper) {
        this.draftMapper = draftMapper;
        this.generationRequestMapper = generationRequestMapper;
        this.resumeMapper = resumeMapper;
        this.userMapper = userMapper;
        this.validator = validator;
        this.normalizer = normalizer;
        this.resumeBuilderDocxService = resumeBuilderDocxService;
        this.careerMaterialsGenerator = careerMaterialsGenerator;
        this.resumeTextExtractor = resumeTextExtractor;
        this.storagePathResolver = storagePathResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResumeBuilderDraftResponse createDraft(String email, CreateResumeBuilderDraftRequest request) {
        User user = getUserByEmail(email);
        validator.validateTemplateType(request.getTemplateType());
        validator.validateLocale(request.getLocale());

        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        ResumeBuilderTemplateLayout.applyForNewDraft(content, request.getTemplateType());

        ResumeBuilderDraft draft = new ResumeBuilderDraft();
        draft.setUserId(user.getId());
        draft.setTitle(request.getTitle().trim());
        draft.setTemplateType(request.getTemplateType());
        draft.setTargetRole(null);
        draft.setLocale(request.getLocale());
        draft.setSchemaVersion(content.getSchemaVersion());
        draft.setDraftJson(writeContent(content));

        draftMapper.insert(draft);
        ResumeBuilderDraft saved = draftMapper.findByIdAndUserId(draft.getId(), user.getId());
        return toResponse(saved == null ? draft : saved, content);
    }

    @Override
    public List<ResumeBuilderDraftSummary> listDrafts(String email) {
        User user = getUserByEmail(email);
        return draftMapper.findAllByUserId(user.getId()).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public ResumeBuilderDraftResponse getDraft(String email, Long draftId) {
        User user = getUserByEmail(email);
        ResumeBuilderDraft draft = getOwnedDraft(draftId, user.getId());
        return toResponse(draft, readContent(draft));
    }

    @Override
    public ResumeBuilderDraftResponse updateDraft(String email, Long draftId, UpdateResumeBuilderDraftRequest request) {
        User user = getUserByEmail(email);
        getOwnedDraft(draftId, user.getId()); // 404s before we ever consider it a conflict

        validator.validateTemplateType(request.getTemplateType());
        validator.validateLocale(request.getLocale());
        ResumeBuilderDraftContent content = request.getContent();
        if (content == null) {
            content = ResumeBuilderDraftContent.blank();
        }
        content.normalize();
        validator.validate(content);

        String targetRole = request.getTargetRole() == null ? null : request.getTargetRole().trim();
        targetRole = syncTargetRole(content, targetRole);
        int updated = draftMapper.updateWithVersion(
                draftId,
                user.getId(),
                request.getTitle().trim(),
                request.getTemplateType(),
                targetRole == null || targetRole.isBlank() ? null : targetRole,
                request.getLocale(),
                content.getSchemaVersion(),
                writeContent(content),
                request.getVersion());

        if (updated == 0) {
            throw new ResumeBuilderDraftConflictException(
                    "This draft changed elsewhere since it was last loaded. Reload before saving.");
        }

        ResumeBuilderDraft saved = getOwnedDraft(draftId, user.getId());
        return toResponse(saved, content);
    }

    @Override
    public ResumeBuilderDraftResponse duplicateDraft(String email, Long draftId) {
        User user = getUserByEmail(email);
        ResumeBuilderDraft original = getOwnedDraft(draftId, user.getId());

        ResumeBuilderDraft copy = new ResumeBuilderDraft();
        copy.setUserId(user.getId());
        copy.setTitle(duplicateTitle(original.getTitle(), original.getLocale()));
        copy.setTemplateType(original.getTemplateType());
        copy.setTargetRole(original.getTargetRole());
        copy.setLocale(original.getLocale());
        copy.setSchemaVersion(original.getSchemaVersion());
        copy.setDraftJson(original.getDraftJson());

        draftMapper.insert(copy);
        ResumeBuilderDraft saved = draftMapper.findByIdAndUserId(copy.getId(), user.getId());
        return toResponse(saved == null ? copy : saved, readContent(saved == null ? copy : saved));
    }

    @Override
    public void deleteDraft(String email, Long draftId) {
        User user = getUserByEmail(email);
        getOwnedDraft(draftId, user.getId());
        int deleted = draftMapper.deleteByIdAndUserId(draftId, user.getId());
        if (deleted == 0) {
            throw new ResumeBuilderDraftNotFoundException("Draft not found");
        }
        // Bookkeeping only -- never touches `resumes`. A resume this draft already
        // generated stays in "我的简历" regardless of the draft's own lifecycle.
        generationRequestMapper.deleteByDraftIdAndUserId(draftId, user.getId());
    }

    @Override
    public NormalizedResume previewDraft(String email, Long draftId) {
        User user = getUserByEmail(email);
        ResumeBuilderDraft draft = getOwnedDraft(draftId, user.getId());
        ResumeBuilderDraftContent content = readContent(draft);
        syncTargetRole(content, draft.getTargetRole());
        validator.validate(content);
        return normalizer.normalize(content, draft.getLocale(), draft.getTemplateType());
    }

    @Override
    public CareerMaterialsResponse getCareerMaterials(String email, Long draftId) {
        User user = getUserByEmail(email);
        ResumeBuilderDraft draft = getOwnedDraft(draftId, user.getId());
        ResumeBuilderDraftContent content = readContent(draft);
        return careerMaterialsGenerator.generate(content, draft.getLocale());
    }

    @Override
    public ResumeResponse generateFromDraft(String email, Long draftId, GenerateResumeBuilderDraftRequest request) {
        User user = getUserByEmail(email);
        ResumeBuilderDraft draft = getOwnedDraft(draftId, user.getId());

        ResumeBuilderGenerationRequest existingClaim =
                generationRequestMapper.findByUserIdAndKey(user.getId(), request.getIdempotencyKey());
        if (existingClaim != null && existingClaim.getResumeId() != null) {
            Resume existingResume = resumeMapper.findById(existingClaim.getResumeId());
            if (existingResume != null && user.getId().equals(existingResume.getUserId())) {
                // Idempotent replay: this exact generate action already succeeded --
                // never write a second file or a second `resumes` row for the same key.
                return toResumeResponse(existingResume);
            }
        }

        ResumeBuilderDraftContent content = readContent(draft);
        syncTargetRole(content, draft.getTargetRole());
        validator.validate(content);
        NormalizedResume normalized = normalizer.normalize(content, draft.getLocale(), draft.getTemplateType());
        if (!normalized.isReadyToGenerate()) {
            throw new InvalidResumeFileException(
                    "Add at least a name or a way to contact you before generating");
        }

        String storedFileName = UUID.randomUUID() + ".docx";
        Path directory = storagePathResolver.resumeDirectory(user.getId());
        Path newPath = directory.resolve(storedFileName).toAbsolutePath().normalize();

        Resume resume = null;
        try {
            Files.createDirectories(newPath.getParent());
            resumeBuilderDocxService.write(normalized, newPath);
            String extractedText = resumeTextExtractor.extract(newPath, "docx");
            if (extractedText == null || extractedText.isBlank()) {
                throw new InvalidResumeFileException("Failed to generate resume");
            }

            resume = new Resume();
            resume.setUserId(user.getId());
            resume.setFileName(generatedFileName(normalized, draft));
            resume.setFileType("docx");
            resume.setFilePath(newPath.toString());
            resume.setExtractedText(extractedText);
            resume.setOrigin(ResumeOrigin.GENERATED.name());
            resume.setReviewStatus(ResumeReviewStatus.READY.name());
            resume.setExtractionStatus(ResumeExtractionStatus.TEXT.name());
            resumeMapper.insert(resume);
            if (resume.getId() == null) {
                throw new IllegalStateException("Failed to save generated resume");
            }

            if (existingClaim == null) {
                ResumeBuilderGenerationRequest claim = new ResumeBuilderGenerationRequest();
                claim.setUserId(user.getId());
                claim.setDraftId(draftId);
                claim.setIdempotencyKey(request.getIdempotencyKey());
                claim.setResumeId(resume.getId());
                try {
                    generationRequestMapper.insert(claim);
                } catch (DuplicateKeyException e) {
                    // Someone else's concurrent request with the SAME idempotency key
                    // (a retry racing the original, or two tabs) won -- undo our own
                    // work and return theirs, so a retry can never leave two resumes.
                    resumeMapper.deleteByIdAndUserId(resume.getId(), user.getId());
                    deleteSavedFile(newPath);
                    ResumeBuilderGenerationRequest winning =
                            generationRequestMapper.findByUserIdAndKey(user.getId(), request.getIdempotencyKey());
                    if (winning != null && winning.getResumeId() != null) {
                        Resume winningResume = resumeMapper.findById(winning.getResumeId());
                        if (winningResume != null) {
                            return toResumeResponse(winningResume);
                        }
                    }
                    throw e;
                }
            } else {
                // The claim row already existed (from a previous attempt whose resume
                // was since deleted) -- reuse it rather than inserting a second row for
                // the same (userId, idempotencyKey), which would violate the unique key.
                generationRequestMapper.updateResumeId(existingClaim.getId(), user.getId(), resume.getId());
            }

            return toResumeResponse(resume);
        } catch (IOException e) {
            if (resume != null && resume.getId() != null) {
                resumeMapper.deleteByIdAndUserId(resume.getId(), user.getId());
            }
            deleteSavedFile(newPath);
            throw new UncheckedIOException("Failed to save generated resume", e);
        } catch (RuntimeException e) {
            if (resume != null && resume.getId() != null) {
                resumeMapper.deleteByIdAndUserId(resume.getId(), user.getId());
            }
            deleteSavedFile(newPath);
            throw e;
        }
    }

    // --- helpers ------------------------------------------------------

    /**
     * Draft-list {@code targetRole} and {@code content.jobTarget.targetTitle} are the
     * same user-facing fact. Copy whichever side is filled into the empty side so
     * preview, generate, and the saved JSON cannot disagree.
     */
    private String syncTargetRole(ResumeBuilderDraftContent content, String targetRole) {
        if (content.getJobTarget() == null) {
            content.setJobTarget(new ResumeBuilderDraftContent.JobTarget());
            content.getJobTarget().normalize();
        }
        String title = content.getJobTarget().getTargetTitle();
        String role = targetRole == null || targetRole.isBlank() ? null : targetRole.trim();
        if ((title == null || title.isBlank()) && role != null) {
            content.getJobTarget().setTargetTitle(role);
            return role;
        }
        if (role == null && title != null && !title.isBlank()) {
            return title.trim();
        }
        return role;
    }

    private User getUserByEmail(String email) {
        User user = userMapper.findByEmail(email);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        return user;
    }

    private ResumeBuilderDraft getOwnedDraft(Long draftId, Long userId) {
        ResumeBuilderDraft draft = draftMapper.findByIdAndUserId(draftId, userId);
        if (draft == null) {
            throw new ResumeBuilderDraftNotFoundException("Draft not found");
        }
        return draft;
    }

    private ResumeBuilderDraftContent readContent(ResumeBuilderDraft draft) {
        try {
            ResumeBuilderDraftContent content = draft.getDraftJson() == null || draft.getDraftJson().isBlank()
                    ? ResumeBuilderDraftContent.blank()
                    : objectMapper.readValue(draft.getDraftJson(), ResumeBuilderDraftContent.class);
            content.normalize();
            return content;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read draft content", e);
        }
    }

    private String writeContent(ResumeBuilderDraftContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to save draft content", e);
        }
    }

    private ResumeBuilderDraftResponse toResponse(ResumeBuilderDraft draft, ResumeBuilderDraftContent content) {
        return new ResumeBuilderDraftResponse(
                draft.getId(),
                draft.getTitle(),
                draft.getTemplateType(),
                draft.getTargetRole(),
                draft.getLocale(),
                draft.getSchemaVersion(),
                draft.getVersion(),
                content,
                draft.getCreatedAt(),
                draft.getUpdatedAt());
    }

    private ResumeBuilderDraftSummary toSummary(ResumeBuilderDraft draft) {
        return new ResumeBuilderDraftSummary(
                draft.getId(),
                draft.getTitle(),
                draft.getTemplateType(),
                draft.getTargetRole(),
                draft.getLocale(),
                draft.getVersion(),
                draft.getCreatedAt(),
                draft.getUpdatedAt());
    }

    private String duplicateTitle(String originalTitle, String locale) {
        String suffix = "zh-CN".equals(locale) ? "（副本）" : " (copy)";
        String base = originalTitle == null ? "" : originalTitle;
        String combined = base + suffix;
        return combined.length() > 200 ? combined.substring(0, 200) : combined;
    }

    private String generatedFileName(NormalizedResume normalized, ResumeBuilderDraft draft) {
        String candidate = normalized.getHeader() != null && normalized.getHeader().getFullName() != null
                && !normalized.getHeader().getFullName().isBlank()
                ? normalized.getHeader().getFullName()
                : draft.getTitle();
        String sanitized = sanitizeFileNameStem(candidate);
        return (sanitized.isBlank() ? "resume" : sanitized) + ".docx";
    }

    private String sanitizeFileNameStem(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").trim();
        cleaned = cleaned.replaceAll("\\s+", " ");
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }

    private void deleteSavedFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup only -- never lets a cleanup failure mask the
            // original error.
        }
    }

    private ResumeResponse toResumeResponse(Resume resume) {
        boolean fileAvailable = resume.getFilePath() != null && Files.exists(Path.of(resume.getFilePath()));
        String downloadUrl = fileAvailable && resume.getId() != null
                ? "/api/v1/resumes/" + resume.getId() + "/download"
                : null;
        return new ResumeResponse(
                resume.getId(),
                resume.getFileName(),
                resume.getFileType(),
                resume.getCreatedAt(),
                resume.getUpdatedAt(),
                resume.getOrigin(),
                resume.getReviewStatus(),
                resume.getParentResumeId(),
                resume.getCanonicalResumeId(),
                fileAvailable,
                downloadUrl);
    }
}
