package aijob.service.impl;

import aijob.config.StoragePathResolver;
import aijob.dto.PdfLayout;
import aijob.dto.ResumeAnalysisResponse;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeFileDownload;
import aijob.dto.ResumeQualityScoreResponse;
import aijob.dto.ResumeResponse;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructure.ResumeBullet;
import aijob.dto.ResumeStructure.ResumeContact;
import aijob.dto.ResumeStructure.ResumeEducation;
import aijob.dto.ResumeStructure.ResumeExperience;
import aijob.dto.ResumeStructure.ResumeProject;
import aijob.dto.ResumeStructure.ResumeSkill;
import aijob.dto.ResumeTextBlock;
import aijob.entity.Resume;
import aijob.entity.User;
import aijob.enums.ResumeExtractionStatus;
import aijob.enums.ResumeOrigin;
import aijob.enums.ResumeReviewStatus;
import aijob.exception.InvalidResumeFileException;
import aijob.exception.ResumeNotFoundException;
import aijob.exception.ResumeTextExtractionException;
import aijob.exception.ResumeTextNotAvailableException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeService;
import aijob.service.ResumeStructureLlmService;
import aijob.service.ResumeTemplateDocxService;
import aijob.util.PdfLayoutExtractor;
import aijob.util.ResumeFileNames;
import aijob.util.ResumeQualityScorer;
import aijob.util.ResumeStructureDraftBuilder;
import aijob.util.LayoutProfileBuilder;
import aijob.util.ResumeTextExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ResumeServiceImpl implements ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeServiceImpl.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".docx");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final ResumeMapper resumeMapper;
    private final UserMapper userMapper;
    private final ResumeTextExtractor resumeTextExtractor;
    private final AiResumeService aiResumeService;
    private final ResumeDocumentService resumeDocumentService;
    private final PdfLayoutExtractor pdfLayoutExtractor;
    private final ResumeStructureDraftBuilder resumeStructureDraftBuilder;
    private final ResumeStructureLlmService resumeStructureLlmService;
    private final ResumeTemplateDocxService resumeTemplateDocxService;
    private final ResumeQualityScorer resumeQualityScorer;
    private final ObjectMapper objectMapper;
    private final StoragePathResolver storagePathResolver;

    public ResumeServiceImpl(ResumeMapper resumeMapper,
                             UserMapper userMapper,
                             ResumeTextExtractor resumeTextExtractor,
                             AiResumeService aiResumeService,
                             ResumeDocumentService resumeDocumentService,
                             PdfLayoutExtractor pdfLayoutExtractor,
                             ResumeStructureDraftBuilder resumeStructureDraftBuilder,
                             ResumeStructureLlmService resumeStructureLlmService,
                             ResumeTemplateDocxService resumeTemplateDocxService,
                             ResumeQualityScorer resumeQualityScorer,
                             ObjectMapper objectMapper,
                             StoragePathResolver storagePathResolver) {
        this.resumeMapper = resumeMapper;
        this.userMapper = userMapper;
        this.resumeTextExtractor = resumeTextExtractor;
        this.aiResumeService = aiResumeService;
        this.resumeDocumentService = resumeDocumentService;
        this.pdfLayoutExtractor = pdfLayoutExtractor;
        this.resumeStructureDraftBuilder = resumeStructureDraftBuilder;
        this.resumeStructureLlmService = resumeStructureLlmService;
        this.resumeTemplateDocxService = resumeTemplateDocxService;
        this.resumeQualityScorer = resumeQualityScorer;
        this.objectMapper = objectMapper;
        this.storagePathResolver = storagePathResolver;
    }

    @Override
    public ResumeResponse uploadResume(String email, MultipartFile file) {

        User user = getUserByEmail(email);
        validateFile(file);

        String originalFileName = ResumeFileNames.uploadName(file.getOriginalFilename());
        String extension = getExtension(originalFileName);
        String fileType = extension.substring(1);
        String storedFileName = UUID.randomUUID() + extension;

        Path directory = storagePathResolver.resumeDirectory(user.getId());
        Path savedPath = directory.resolve(storedFileName).toAbsolutePath().normalize();

        try {
            Files.createDirectories(savedPath.getParent());
            file.transferTo(savedPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save resume file", e);
        }

        try {
            String extractedText =
                    resumeTextExtractor.extract(savedPath, fileType);

            Resume resume = new Resume();
            resume.setUserId(user.getId());
            resume.setFileName(originalFileName);
            resume.setFileType(fileType);
            resume.setFilePath(savedPath.toString());
            resume.setExtractedText(extractedText);
            resume.setOrigin(ResumeOrigin.UPLOAD.name());

            if ("pdf".equalsIgnoreCase(fileType)) {
                resume.setReviewStatus(ResumeReviewStatus.PENDING_REVIEW.name());
                applyPdfLayout(resume, savedPath);
            } else {
                resume.setReviewStatus(ResumeReviewStatus.READY.name());
                resume.setExtractionStatus(ResumeExtractionStatus.TEXT.name());
            }

            resumeMapper.insert(resume);

            Resume saved = resumeMapper.findById(resume.getId());
            return toResumeResponse(saved != null ? saved : resume);
        } catch (ResumeTextExtractionException e) {
            deleteSavedFile(savedPath);
            throw e;
        } catch (RuntimeException e) {
            deleteSavedFile(savedPath);
            throw e;
        }
    }

    @Override
    public List<ResumeResponse> getMyResumes(String email, boolean includeDerived) {

        User user = getUserByEmail(email);

        return resumeMapper.findByUserId(user.getId())
                .stream()
                .filter(resume -> includeDerived || isVisibleByDefault(resume))
                .map(this::toResumeResponse)
                .toList();
    }

    @Override
    public ResumeResponse getResume(String email, Long id) {

        User user = getUserByEmail(email);
        Resume resume = getOwnedResume(id, user.getId());

        return toResumeResponse(resume);
    }

    @Override
    public ResumeQualityScoreResponse getQualityScore(String email, Long resumeId) {

        User user = getUserByEmail(email);
        Resume resume = getOwnedResume(resumeId, user.getId());

        // Deliberately just the raw extracted text -- see ResumeQualityScorer's
        // javadoc for why this never touches structuredJson/layoutJson (which would
        // couple scoring to whether PDF structure classification has run) and never
        // calls resumeStructureLlmService, aiResumeService, or resumeDocumentService.
        return resumeQualityScorer.score(resume.getExtractedText());
    }

    @Override
    public void deleteResume(String email, Long id) {

        User user = getUserByEmail(email);
        Resume resume = getOwnedResume(id, user.getId());

        int deleted = resumeMapper.deleteByIdAndUserId(id, user.getId());

        if (deleted == 0) {
            throw new ResumeNotFoundException("Resume not found");
        }

        deleteOwnedResumeFileIfPresent(resume, user.getId());
    }

    @Override
    @Transactional
    public void deleteResumeFamily(String email, Long id) {

        User user = getUserByEmail(email);
        Resume parent = getOwnedResume(id, user.getId());
        if (!"pdf".equalsIgnoreCase(parent.getFileType())) {
            throw new InvalidResumeFileException("Family delete is only available for PDF resumes");
        }

        List<Resume> children = familyChildren(parent, user.getId());
        List<Resume> family = new ArrayList<>();
        family.add(parent);
        family.addAll(children);

        for (Resume member : family) {
            resumeMapper.clearCanonicalResumeId(user.getId(), member.getId());
            resumeMapper.clearParentResumeId(user.getId(), member.getId());
        }

        for (Resume child : children) {
            int deletedChild = resumeMapper.deleteByIdAndUserId(child.getId(), user.getId());
            if (deletedChild == 0) {
                throw new ResumeNotFoundException("Resume not found");
            }
        }

        int deletedParent = resumeMapper.deleteByIdAndUserId(parent.getId(), user.getId());
        if (deletedParent == 0) {
            throw new ResumeNotFoundException("Resume not found");
        }

        for (Resume member : family) {
            deleteOwnedResumeFileIfPresent(member, user.getId());
        }
    }

    @Override
    public ResumeAnalysisResponse analyzeResume(String email, Long resumeId) {

        User user = getUserByEmail(email);
        Resume resume = getOwnedResume(resumeId, user.getId());

        String extractedText = resume.getExtractedText();

        if (extractedText == null || extractedText.isBlank()) {
            throw new ResumeTextNotAvailableException(
                    "Resume text is not available for analysis"
            );
        }

        return aiResumeService.analyzeResume(extractedText);
    }

    @Override
    public List<ResumeDocumentNode> getDocumentNodes(String email, Long resumeId) {

        User user = getUserByEmail(email);
        Resume resume = getOwnedResume(resumeId, user.getId());

        if (!"docx".equalsIgnoreCase(resume.getFileType())) {
            throw new InvalidResumeFileException(
                    "Document node parsing currently supports DOCX only"
            );
        }

        Path filePath = Path.of(resume.getFilePath());
        if (!Files.exists(filePath)) {
            throw new InvalidResumeFileException("Resume file not found");
        }

        return resumeDocumentService.parseDocx(filePath);
    }

    @Override
    public ResumeStructure getResumeStructure(String email, Long resumeId) {

        User user = getUserByEmail(email);
        Resume resume = getOwnedPdfResume(resumeId, user.getId());

        boolean layoutMissing = isBlank(resume.getLayoutJson());
        boolean structureMissing = isBlank(resume.getStructuredJson());
        if (layoutMissing || structureMissing) {
            Path filePath = Path.of(resume.getFilePath());
            if (layoutMissing) {
                applyPdfLayout(resume, filePath);
            } else if (structureMissing) {
                PdfLayout layout = readLayout(resume.getLayoutJson());
                ResumeStructure draft = resumeStructureDraftBuilder.build(layout);
                draft.setResumeId(resume.getId());
                draft.setReviewStatus(ResumeReviewStatus.PENDING_REVIEW.name());
                draft.normalize();
                resume.setStructuredJson(writeJson(draft));
                resume.setReviewStatus(ResumeReviewStatus.PENDING_REVIEW.name());
            }
            persistLayoutAndStructure(resume);
        }

        ResumeStructure structure = readStoredStructure(resume);
        if (structure == null) {
            structure = ResumeStructure.empty();
        }
        structure.setResumeId(resume.getId());
        structure.setReviewStatus(resume.getReviewStatus());
        structure.setExtractionStatus(resume.getExtractionStatus());
        structure.normalize();

        PdfLayout layout = readLayout(resume.getLayoutJson());
        if (shouldClassify(resume, structure, layout)) {
            try {
                ResumeStructure classified =
                        resumeStructureLlmService.classify(layout, structure);
                if (classified != null) {
                    classified.setResumeId(resume.getId());
                    classified.setReviewStatus(ResumeReviewStatus.PENDING_REVIEW.name());
                    classified.setExtractionStatus(resume.getExtractionStatus());
                    classified.setLlmClassified(Boolean.TRUE);
                    classified.setStructureFormatVersion(ResumeStructure.STRUCTURE_FORMAT_VERSION);
                    classified.normalize();
                    persistStructure(
                            resume,
                            classified,
                            ResumeReviewStatus.PENDING_REVIEW.name()
                    );
                    classified.setExtractionStatus(resume.getExtractionStatus());
                    return classified;
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Resume structure classification failed; returning rule draft: {}",
                        e.getClass().getSimpleName()
                );
            }
        }

        return structure;
    }

    @Override
    public ResumeStructure updateResumeStructure(
            String email,
            Long resumeId,
            ResumeStructure structure) {

        User user = getUserByEmail(email);
        Resume resume = getOwnedPdfResume(resumeId, user.getId());
        String originalFilePath = resume.getFilePath();
        String originalLayoutJson = resume.getLayoutJson();
        String originalExtractedText = resume.getExtractedText();

        ResumeStructure toStore = structure == null ? ResumeStructure.empty() : structure;
        toStore.setResumeId(resume.getId());
        toStore.setReviewStatus(ResumeReviewStatus.PENDING_REVIEW.name());
        toStore.setExtractionStatus(resume.getExtractionStatus());
        toStore.setLlmClassified(Boolean.TRUE);
        toStore.setStructureFormatVersion(ResumeStructure.STRUCTURE_FORMAT_VERSION);
        toStore.normalize();
        applyUserEditedProvenance(toStore);

        persistStructure(resume, toStore, ResumeReviewStatus.PENDING_REVIEW.name());

        resume.setFilePath(originalFilePath);
        resume.setLayoutJson(originalLayoutJson);
        resume.setExtractedText(originalExtractedText);

        toStore.setExtractionStatus(resume.getExtractionStatus());
        return toStore;
    }

    @Override
    @Transactional
    public ResumeResponse canonicalizeResume(String email, Long pdfResumeId) {
        User user = getUserByEmail(email);
        Resume parent = getOwnedPdfParent(pdfResumeId, user.getId());
        String originalParentPath = parent.getFilePath();

        ResumeStructure structure = preparedStructure(readStoredStructure(parent));
        ensureLayoutProfile(structure, parent);
        validateCanonicalStructure(structure);

        Resume existingChild = loadCanonicalChild(parent, user.getId());
        Path oldChildPath = existingChild == null || isBlank(existingChild.getFilePath())
                ? null
                : Path.of(existingChild.getFilePath());

        String storedFileName = UUID.randomUUID() + ".docx";
        Path directory = storagePathResolver.resumeDirectory(user.getId());
        Path newPath = directory.resolve(storedFileName).toAbsolutePath().normalize();

        try {
            Files.createDirectories(newPath.getParent());
            resumeTemplateDocxService.write(structure, newPath);
            String extractedText = resumeTextExtractor.extract(newPath, "docx");
            if (extractedText == null || extractedText.isBlank()) {
                throw new InvalidResumeFileException("Failed to generate canonical resume");
            }

            String displayName = canonicalDisplayName(structure.getContact().getFullName());
            Resume child = persistCanonicalChild(
                    parent,
                    existingChild,
                    user.getId(),
                    displayName,
                    newPath.toString(),
                    extractedText
            );
            parent.setFilePath(originalParentPath);
            if (oldChildPath != null && !oldChildPath.equals(newPath)) {
                deleteSavedFile(oldChildPath);
            }
            return toResumeResponse(child);
        } catch (IOException e) {
            deleteSavedFile(newPath);
            throw new UncheckedIOException("Failed to save canonical resume", e);
        } catch (RuntimeException e) {
            deleteSavedFile(newPath);
            throw e;
        }
    }

    @Override
    public ResumeFileDownload downloadResume(String email, Long resumeId) {
        User user = getUserByEmail(email);
        Resume resume = getOwnedResume(resumeId, user.getId());
        Path resolved = resolveAvailableResumeFile(resume, user.getId());
        if (resolved == null) {
            throw new ResumeNotFoundException("Resume not found");
        }

        String fileType = resume.getFileType() == null
                ? ""
                : resume.getFileType().toLowerCase(Locale.ROOT);
        String fileName = ResumeFileNames.downloadName(resume.getFileName(), fileType);
        return new ResumeFileDownload(resolved, fileName, fileType);
    }

    private Resume persistCanonicalChild(
            Resume parent,
            Resume existingChild,
            Long userId,
            String fileName,
            String filePath,
            String extractedText) {

        if (existingChild == null) {
            Resume child = new Resume();
            child.setUserId(userId);
            child.setFileName(fileName);
            child.setFileType("docx");
            child.setFilePath(filePath);
            child.setExtractedText(extractedText);
            child.setParentResumeId(parent.getId());
            child.setOrigin(ResumeOrigin.TEMPLATE.name());
            child.setReviewStatus(ResumeReviewStatus.READY.name());
            child.setExtractionStatus(ResumeExtractionStatus.TEXT.name());
            if (resumeMapper.insert(child) == 0 || child.getId() == null) {
                throw new IllegalStateException("Failed to save canonical resume");
            }
            int updated = resumeMapper.updateCanonicalParent(
                    parent.getId(),
                    userId,
                    child.getId(),
                    ResumeReviewStatus.READY.name()
            );
            if (updated == 0) {
                throw new ResumeNotFoundException("Resume not found");
            }
            parent.setCanonicalResumeId(child.getId());
            parent.setReviewStatus(ResumeReviewStatus.READY.name());
            return child;
        }

        int updatedChild = resumeMapper.updateCanonicalChildFile(
                existingChild.getId(),
                userId,
                fileName,
                "docx",
                filePath,
                extractedText,
                ResumeReviewStatus.READY.name(),
                ResumeExtractionStatus.TEXT.name()
        );
        if (updatedChild == 0) {
            throw new ResumeNotFoundException("Resume not found");
        }
        int updatedParent = resumeMapper.updateCanonicalParent(
                parent.getId(),
                userId,
                existingChild.getId(),
                ResumeReviewStatus.READY.name()
        );
        if (updatedParent == 0) {
            throw new ResumeNotFoundException("Resume not found");
        }
        existingChild.setFileName(fileName);
        existingChild.setFileType("docx");
        existingChild.setFilePath(filePath);
        existingChild.setExtractedText(extractedText);
        existingChild.setReviewStatus(ResumeReviewStatus.READY.name());
        existingChild.setExtractionStatus(ResumeExtractionStatus.TEXT.name());
        parent.setCanonicalResumeId(existingChild.getId());
        parent.setReviewStatus(ResumeReviewStatus.READY.name());
        return existingChild;
    }

    private Resume loadCanonicalChild(Resume parent, Long userId) {
        Long childId = parent.getCanonicalResumeId();
        if (childId == null) {
            return null;
        }
        Resume child = resumeMapper.findById(childId);
        if (child == null || !userId.equals(child.getUserId())) {
            return null;
        }
        if (parent.getId() != null && !parent.getId().equals(child.getParentResumeId())) {
            return null;
        }
        return child;
    }

    private ResumeStructure preparedStructure(ResumeStructure stored) {
        if (stored == null) {
            throw new InvalidResumeFileException("Resume structure is required before canonicalize");
        }
        ResumeStructure structure = objectMapper.convertValue(stored, ResumeStructure.class);
        structure.normalize();
        ResumeContact contact = structure.getContact();
        contact.setFullName(trimToEmpty(contact.getFullName()));
        contact.setEmail(trimToEmpty(contact.getEmail()));
        contact.setPhone(trimToEmpty(contact.getPhone()));
        contact.setLocation(trimToEmpty(contact.getLocation()));
        contact.setLinks(cleanStrings(contact.getLinks()));

        List<ResumeBullet> bullets = new ArrayList<>();
        for (ResumeBullet bullet : structure.getSummaryBullets()) {
            if (bullet == null) {
                continue;
            }
            bullet.setText(trimToEmpty(bullet.getText()));
            if (!bullet.getText().isBlank()) {
                bullets.add(bullet);
            }
        }
        structure.setSummaryBullets(bullets);

        List<ResumeSkill> skills = new ArrayList<>();
        for (ResumeSkill skill : structure.getSkills()) {
            if (skill == null) {
                continue;
            }
            skill.setName(trimToEmpty(skill.getName()));
            if (!skill.getName().isBlank()) {
                skills.add(skill);
            }
        }
        structure.setSkills(skills);

        for (ResumeExperience job : structure.getExperience()) {
            if (job == null) {
                continue;
            }
            job.setCompany(trimToEmpty(job.getCompany()));
            job.setTitle(trimToEmpty(job.getTitle()));
            job.setStartDate(trimToEmpty(job.getStartDate()));
            job.setEndDate(trimToEmpty(job.getEndDate()));
            job.setLocation(trimToEmpty(job.getLocation()));
            job.setBullets(cleanStrings(job.getBullets()));
        }
        for (ResumeProject project : structure.getProjects()) {
            if (project == null) {
                continue;
            }
            project.setName(trimToEmpty(project.getName()));
            project.setOrganizationOrType(trimToEmpty(project.getOrganizationOrType()));
            project.setDates(trimToEmpty(project.getDates()));
            project.setBullets(cleanStrings(project.getBullets()));
            project.setLinks(cleanStrings(project.getLinks()));
        }
        for (ResumeEducation school : structure.getEducation()) {
            if (school == null) {
                continue;
            }
            school.setSchool(trimToEmpty(school.getSchool()));
            school.setDegree(trimToEmpty(school.getDegree()));
            school.setDates(trimToEmpty(school.getDates()));
            school.setDetails(trimToEmpty(school.getDetails()));
        }
        return structure;
    }

    private void ensureLayoutProfile(ResumeStructure structure, Resume parent) {
        if (structure.getLayoutProfile() != null
                && structure.getLayoutProfile().getConfidence() != null
                && !structure.getLayoutProfile().getConfidence().isBlank()) {
            structure.getLayoutProfile().normalize();
            return;
        }
        structure.setLayoutProfile(LayoutProfileBuilder.from(readLayout(parent.getLayoutJson())));
    }

    private void validateCanonicalStructure(ResumeStructure structure) {
        if (structure.getContact() == null || structure.getContact().getFullName().isBlank()) {
            throw new InvalidResumeFileException("Full name is required");
        }
        if (!structure.getSkills().isEmpty()) {
            return;
        }
        for (ResumeExperience job : structure.getExperience()) {
            if (job != null && job.getBullets() != null && !job.getBullets().isEmpty()) {
                return;
            }
        }
        for (ResumeProject project : structure.getProjects()) {
            if (project != null && project.getBullets() != null && !project.getBullets().isEmpty()) {
                return;
            }
        }
        throw new InvalidResumeFileException(
                "Resume must include skills or at least one experience or project bullet"
        );
    }

    private List<String> cleanStrings(List<String> values) {
        List<String> cleaned = new ArrayList<>();
        if (values == null) {
            return cleaned;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String canonicalDisplayName(String fullName) {
        String base = fullName.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        if (base.isBlank()) {
            base = "resume";
        }
        return base + "_canonical.docx";
    }

    private Resume getOwnedPdfParent(Long resumeId, Long userId) {
        Resume resume = getOwnedResume(resumeId, userId);
        if (!"pdf".equalsIgnoreCase(resume.getFileType())) {
            throw new InvalidResumeFileException("Canonicalize is only available for PDF resumes");
        }
        return resume;
    }

    private void applyPdfLayout(Resume resume, Path filePath) {
        PdfLayout layout = pdfLayoutExtractor.extract(filePath);
        if (layout == null) {
            layout = PdfLayout.imageOnly();
        }
        if (layout.getStatus() == null || layout.getStatus().isBlank()) {
            layout.setStatus(layout.getBlocks() == null || layout.getBlocks().isEmpty()
                    ? ResumeExtractionStatus.IMAGE_ONLY.name()
                    : ResumeExtractionStatus.LAYOUT.name());
        }
        ResumeStructure draft = resumeStructureDraftBuilder.build(layout);
        draft.setResumeId(resume.getId());
        draft.setReviewStatus(ResumeReviewStatus.PENDING_REVIEW.name());
        draft.normalize();
        resume.setLayoutJson(writeJson(layout));
        resume.setExtractionStatus(layout.getStatus());
        resume.setStructuredJson(writeJson(draft));
        resume.setReviewStatus(ResumeReviewStatus.PENDING_REVIEW.name());
    }

    private void persistLayoutAndStructure(Resume resume) {
        int updated = resumeMapper.updateLayoutAndStructure(
                resume.getId(),
                resume.getUserId(),
                resume.getLayoutJson(),
                resume.getExtractionStatus(),
                resume.getStructuredJson(),
                resume.getReviewStatus()
        );
        if (updated == 0) {
            throw new ResumeNotFoundException("Resume not found");
        }
    }

    private void persistStructure(Resume resume, ResumeStructure structure, String reviewStatus) {
        String json = writeJson(structure);
        int updated = resumeMapper.updateStructure(
                resume.getId(),
                resume.getUserId(),
                json,
                reviewStatus
        );
        if (updated == 0) {
            throw new ResumeNotFoundException("Resume not found");
        }
        resume.setStructuredJson(json);
        resume.setReviewStatus(reviewStatus);
    }

    private void applyUserEditedProvenance(ResumeStructure structure) {
        var provenance = structure.getFieldProvenance();
        var contact = structure.getContact();
        stampUserEditedIfAbsent(provenance, "contact.fullName", contact.getFullName());
        stampUserEditedIfAbsent(provenance, "contact.email", contact.getEmail());
        stampUserEditedIfAbsent(provenance, "contact.phone", contact.getPhone());
        stampUserEditedIfAbsent(provenance, "contact.location", contact.getLocation());
        if (!contact.getLinks().isEmpty()) {
            provenance.putIfAbsent("contact.links", ResumeTextBlock.PROVENANCE_USER_EDITED);
        }
        stampUserEditedIfAbsent(provenance, "summary", structure.getSummary());
        if (!structure.getSummaryBullets().isEmpty()) {
            provenance.putIfAbsent("summaryBullets", ResumeTextBlock.PROVENANCE_USER_EDITED);
        }
        if (!structure.getSkills().isEmpty()) {
            provenance.putIfAbsent("skills", ResumeTextBlock.PROVENANCE_USER_EDITED);
        }
        if (!structure.getExperience().isEmpty()) {
            provenance.putIfAbsent("experience", ResumeTextBlock.PROVENANCE_USER_EDITED);
        }
        if (!structure.getEducation().isEmpty()) {
            provenance.putIfAbsent("education", ResumeTextBlock.PROVENANCE_USER_EDITED);
        }
        if (!structure.getProjects().isEmpty()) {
            provenance.putIfAbsent("projects", ResumeTextBlock.PROVENANCE_USER_EDITED);
        }
    }

    private boolean shouldClassify(Resume resume, ResumeStructure structure, PdfLayout layout) {
        if (ResumeExtractionStatus.IMAGE_ONLY.name().equals(resume.getExtractionStatus())) {
            return false;
        }
        if (layout == null || layout.getBlocks() == null || layout.getBlocks().isEmpty()) {
            return false;
        }
        if (structure.getFieldProvenance() != null
                && structure.getFieldProvenance().containsValue(ResumeTextBlock.PROVENANCE_USER_EDITED)) {
            return false;
        }
        return !structure.hasCurrentLlmFormat();
    }

    private void stampUserEditedIfAbsent(Map<String, String> provenance, String key, String value) {
        if (value != null && !value.isBlank()) {
            provenance.putIfAbsent(key, ResumeTextBlock.PROVENANCE_USER_EDITED);
        }
    }

    private Resume getOwnedPdfResume(Long resumeId, Long userId) {
        Resume resume = getOwnedResume(resumeId, userId);
        if (!"pdf".equalsIgnoreCase(resume.getFileType())) {
            throw new InvalidResumeFileException(
                    "Resume structure is only available for PDF resumes"
            );
        }
        return resume;
    }

    private ResumeStructure readStoredStructure(Resume resume) {
        String json = resume.getStructuredJson();
        if (isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ResumeStructure.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private PdfLayout readLayout(String json) {
        if (isBlank(json)) {
            return PdfLayout.imageOnly();
        }
        try {
            return objectMapper.readValue(json, PdfLayout.class);
        } catch (JsonProcessingException e) {
            return PdfLayout.imageOnly();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize resume structure", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void deleteSavedFile(Path savedPath) {
        try {
            Files.deleteIfExists(savedPath);
        } catch (IOException ignored) {
            // keep the original error
        }
    }

    private User getUserByEmail(String email) {

        User user = userMapper.findByEmail(email);

        if (user == null) {
            throw new UserNotFoundException("User not found");
        }

        return user;
    }

    private Resume getOwnedResume(Long id, Long userId) {

        Resume resume = resumeMapper.findById(id);

        if (resume == null || !userId.equals(resume.getUserId())) {
            throw new ResumeNotFoundException("Resume not found");
        }

        return resume;
    }

    private void validateFile(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new InvalidResumeFileException("File cannot be empty");
        }

        // Checked on the decoded, cleaned name that will be stored, so an encoded
        // "cv.pdf%00.exe" is judged as "cv.pdf.exe", not as a .exe hidden behind a NUL.
        String originalFileName = ResumeFileNames.uploadName(file.getOriginalFilename());
        if (originalFileName.isBlank()) {
            throw new InvalidResumeFileException("File name is required");
        }

        String extension = getExtension(originalFileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidResumeFileException("Only PDF and DOCX files are allowed");
        }

        String contentType = file.getContentType();
        if (contentType != null
                && !contentType.isBlank()
                && !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))
                && !"application/octet-stream".equalsIgnoreCase(contentType)) {
            throw new InvalidResumeFileException("Only PDF and DOCX files are allowed");
        }
    }

    private String getExtension(String fileName) {

        int index = fileName.lastIndexOf('.');
        if (index < 0) {
            return "";
        }

        return fileName.substring(index).toLowerCase(Locale.ROOT);
    }

    private ResumeResponse toResumeResponse(Resume resume) {

        boolean fileAvailable = resolveAvailableResumeFile(resume, resume.getUserId()) != null;
        String downloadUrl = fileAvailable && resume.getId() != null
                ? "/api/v1/resumes/" + resume.getId() + "/download"
                : null;
        return new ResumeResponse(
                resume.getId(),
                ResumeFileNames.displayName(resume.getFileName()),
                resume.getFileType(),
                resume.getCreatedAt(),
                resume.getUpdatedAt(),
                resume.getOrigin(),
                resume.getReviewStatus(),
                resume.getParentResumeId(),
                resume.getCanonicalResumeId(),
                fileAvailable,
                downloadUrl
        );
    }

    private Path resolveAvailableResumeFile(Resume resume, Long userId) {
        if (resume == null || userId == null) {
            return null;
        }
        String fileType = resume.getFileType() == null ? "" : resume.getFileType().toLowerCase(Locale.ROOT);
        if (!"pdf".equals(fileType) && !"docx".equals(fileType)) {
            return null;
        }
        if (isBlank(resume.getFilePath())) {
            return null;
        }

        Path allowedRoot = storagePathResolver.resumeDirectory(userId).toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = Path.of(resume.getFilePath()).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return null;
        }
        if (!resolved.startsWith(allowedRoot)
                || !allowedRoot.equals(resolved.getParent())
                || !Files.isRegularFile(resolved)) {
            return null;
        }
        return resolved;
    }

    private void deleteOwnedResumeFileIfPresent(Resume resume, Long userId) {
        Path resolved = resolveAvailableResumeFile(resume, userId);
        if (resolved != null) {
            deleteSavedFile(resolved);
        }
    }

    private List<Resume> familyChildren(Resume parent, Long userId) {
        LinkedHashMap<Long, Resume> children = new LinkedHashMap<>();
        Long childId = parent.getCanonicalResumeId();
        if (childId != null) {
            Resume child = resumeMapper.findById(childId);
            if (child != null && userId.equals(child.getUserId()) && child.getId() != null) {
                children.put(child.getId(), child);
            }
        }
        List<Resume> byParent = resumeMapper.findByParentResumeIdAndUserId(parent.getId(), userId);
        if (byParent != null) {
            for (Resume child : byParent) {
                if (child != null && child.getId() != null) {
                    children.put(child.getId(), child);
                }
            }
        }
        return new ArrayList<>(children.values());
    }

    private boolean isUploadOrigin(Resume resume) {
        return resume.getOrigin() == null || ResumeOrigin.UPLOAD.name().equals(resume.getOrigin());
    }

    /** UPLOAD and GENERATED are real, standalone resumes and always show in "我的简历"
     * by default. TEMPLATE is the internal canonical-DOCX working copy of a PDF and
     * stays hidden unless {@code includeDerived} is requested -- see ResumeOrigin's
     * javadoc. */
    private boolean isVisibleByDefault(Resume resume) {
        return isUploadOrigin(resume) || ResumeOrigin.GENERATED.name().equals(resume.getOrigin());
    }

}
