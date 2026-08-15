package aijob.service.impl;

import aijob.dto.ResumeAnalysisResponse;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeResponse;
import aijob.entity.Resume;
import aijob.entity.User;
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
import aijob.util.ResumeTextExtractor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ResumeServiceImpl implements ResumeService {

    private static final Path UPLOAD_ROOT = Path.of("uploads", "resumes");
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

    public ResumeServiceImpl(ResumeMapper resumeMapper,
                             UserMapper userMapper,
                             ResumeTextExtractor resumeTextExtractor,
                             AiResumeService aiResumeService,
                             ResumeDocumentService resumeDocumentService) {
        this.resumeMapper = resumeMapper;
        this.userMapper = userMapper;
        this.resumeTextExtractor = resumeTextExtractor;
        this.aiResumeService = aiResumeService;
        this.resumeDocumentService = resumeDocumentService;
    }

    @Override
    public ResumeResponse uploadResume(String email, MultipartFile file) {

        User user = getUserByEmail(email);
        validateFile(file);

        String originalFileName = sanitizeOriginalFileName(file.getOriginalFilename());
        String extension = getExtension(originalFileName);
        String fileType = extension.substring(1);
        String storedFileName = UUID.randomUUID() + extension;

        Path directory = UPLOAD_ROOT.resolve(String.valueOf(user.getId()));
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
    public List<ResumeResponse> getMyResumes(String email) {

        User user = getUserByEmail(email);

        return resumeMapper.findByUserId(user.getId())
                .stream()
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
    public void deleteResume(String email, Long id) {

        User user = getUserByEmail(email);
        Resume resume = getOwnedResume(id, user.getId());

        int deleted = resumeMapper.deleteByIdAndUserId(id, user.getId());

        if (deleted == 0) {
            throw new ResumeNotFoundException("Resume not found");
        }

        try {
            Files.deleteIfExists(Path.of(resume.getFilePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete resume file", e);
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

        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.isBlank()) {
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

    private String sanitizeOriginalFileName(String originalFileName) {

        String fileName = Path.of(originalFileName).getFileName().toString();

        if (fileName.length() > 255) {
            return fileName.substring(0, 255);
        }

        return fileName;
    }

    private String getExtension(String fileName) {

        int index = fileName.lastIndexOf('.');
        if (index < 0) {
            return "";
        }

        return fileName.substring(index).toLowerCase(Locale.ROOT);
    }

    private ResumeResponse toResumeResponse(Resume resume) {

        return new ResumeResponse(
                resume.getId(),
                resume.getFileName(),
                resume.getFileType(),
                resume.getCreatedAt(),
                resume.getUpdatedAt()
        );
    }
}
