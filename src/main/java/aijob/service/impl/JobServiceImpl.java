package aijob.service.impl;

import aijob.dto.CreateJobRequest;
import aijob.dto.JobMatchResponse;
import aijob.dto.JobResponse;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.UpdateJobRequest;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.User;
import aijob.enums.TailorMode;
import aijob.exception.InvalidResumeFileException;
import aijob.exception.JobNotFoundException;
import aijob.exception.ResumeNotFoundException;
import aijob.exception.ResumeTextNotAvailableException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.JobService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import aijob.util.ResumeTailorNodeGuard;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class JobServiceImpl implements JobService {

    private static final String SOURCE_MANUAL = "MANUAL";
    private static final String STATUS_SAVED = "SAVED";

    private final JobMapper jobMapper;
    private final UserMapper userMapper;
    private final ResumeMapper resumeMapper;
    private final AiResumeService aiResumeService;
    private final ResumeDocumentService resumeDocumentService;
    private final ResumeDocxTailorService resumeDocxTailorService;

    public JobServiceImpl(JobMapper jobMapper,
                          UserMapper userMapper,
                          ResumeMapper resumeMapper,
                          AiResumeService aiResumeService,
                          ResumeDocumentService resumeDocumentService,
                          ResumeDocxTailorService resumeDocxTailorService) {
        this.jobMapper = jobMapper;
        this.userMapper = userMapper;
        this.resumeMapper = resumeMapper;
        this.aiResumeService = aiResumeService;
        this.resumeDocumentService = resumeDocumentService;
        this.resumeDocxTailorService = resumeDocxTailorService;
    }

    @Override
    public JobResponse createJob(String email, CreateJobRequest request) {

        User user = getUserByEmail(email);

        Job job = new Job();
        job.setUserId(user.getId());
        job.setCompany(request.getCompany().trim());
        job.setTitle(request.getTitle().trim());
        job.setLocation(trimToNull(request.getLocation()));
        job.setJobUrl(trimToNull(request.getJobUrl()));
        job.setDescription(request.getDescription().trim());
        job.setSource(SOURCE_MANUAL);
        job.setStatus(STATUS_SAVED);

        jobMapper.insert(job);

        Job saved = jobMapper.findById(job.getId());
        return toJobResponse(saved != null ? saved : job);
    }

    @Override
    public List<JobResponse> getMyJobs(String email) {

        User user = getUserByEmail(email);

        return jobMapper.findByUserId(user.getId())
                .stream()
                .map(this::toJobResponse)
                .toList();
    }

    @Override
    public JobResponse getJob(String email, Long id) {

        User user = getUserByEmail(email);
        Job job = getOwnedJob(id, user.getId());

        return toJobResponse(job);
    }

    @Override
    public JobResponse updateJob(
            String email,
            Long id,
            UpdateJobRequest request) {

        User user = getUserByEmail(email);
        Job job = getOwnedJob(id, user.getId());

        job.setCompany(request.getCompany().trim());
        job.setTitle(request.getTitle().trim());
        job.setLocation(trimToNull(request.getLocation()));
        job.setJobUrl(trimToNull(request.getJobUrl()));
        job.setDescription(request.getDescription().trim());

        int updated = jobMapper.update(job);

        if (updated == 0) {
            throw new JobNotFoundException("Job not found");
        }

        Job saved = jobMapper.findById(job.getId());
        return toJobResponse(saved != null ? saved : job);
    }

    @Override
    public void deleteJob(String email, Long id) {

        User user = getUserByEmail(email);
        getOwnedJob(id, user.getId());

        int deleted = jobMapper.deleteByIdAndUserId(id, user.getId());

        if (deleted == 0) {
            throw new JobNotFoundException("Job not found");
        }
    }

    @Override
    public JobMatchResponse matchJob(
            String email,
            Long jobId,
            Long resumeId) {

        User user = getUserByEmail(email);
        Job job = getOwnedJob(jobId, user.getId());
        Resume resume = getOwnedResume(resumeId, user.getId());

        String resumeText = resume.getExtractedText();
        if (resumeText == null || resumeText.isBlank()) {
            throw new ResumeTextNotAvailableException(
                    "Resume text is not available for analysis"
            );
        }

        String jobDescription = job.getDescription();
        if (jobDescription == null || jobDescription.isBlank()) {
            throw new ResumeTextNotAvailableException(
                    "Job description is not available for matching"
            );
        }

        return aiResumeService.matchResumeToJob(
                resumeText,
                jobDescription
        );
    }

    @Override
    public TailoredResumeResponse tailorResume(
            String email,
            Long jobId,
            Long resumeId,
            TailorMode mode) {

        TailorMode resolvedMode =
                mode == null ? TailorMode.CONSERVATIVE : mode;

        User user = getUserByEmail(email);
        Job job = getOwnedJob(jobId, user.getId());
        Resume resume = getOwnedResume(resumeId, user.getId());

        if (!"docx".equalsIgnoreCase(resume.getFileType())) {
            throw new InvalidResumeFileException(
                    "Resume tailoring currently supports DOCX only"
            );
        }

        String jobDescription = job.getDescription();
        if (jobDescription == null || jobDescription.isBlank()) {
            throw new ResumeTextNotAvailableException(
                    "Job description is not available for matching"
            );
        }

        Path originalPath = Path.of(resume.getFilePath());
        if (!Files.exists(originalPath)) {
            throw new InvalidResumeFileException("Resume file not found");
        }

        List<ResumeDocumentNode> nodes =
                resumeDocumentService.parseDocx(originalPath);

        List<TailorChange> proposed =
                aiResumeService.generateTailorChanges(
                        nodes,
                        jobDescription,
                        resolvedMode
                );

        TailorFilterResult filtered =
                filterSafeChanges(nodes, proposed, resolvedMode);
        List<TailorChange> accepted = filtered.accepted();
        List<RejectedTailorChange> rejected = filtered.rejected();

        List<TailorChange> verified = accepted.stream()
                .filter(change -> Boolean.TRUE.equals(change.getApplied()))
                .toList();

        Path generatedPath = resumeDocxTailorService.createTailoredDocx(
                originalPath,
                verified,
                user.getId(),
                job.getId()
        );

        int appliedChangeCount = verified.size();

        return new TailoredResumeResponse(
                resume.getId(),
                job.getId(),
                resolvedMode,
                generatedPath.getFileName().toString(),
                generatedPath.toString(),
                accepted,
                appliedChangeCount == 0,
                appliedChangeCount,
                rejected.size(),
                rejected
        );
    }

    private User getUserByEmail(String email) {

        User user = userMapper.findByEmail(email);

        if (user == null) {
            throw new UserNotFoundException("User not found");
        }

        return user;
    }

    private Job getOwnedJob(Long id, Long userId) {

        Job job = jobMapper.findById(id);

        if (job == null || !userId.equals(job.getUserId())) {
            throw new JobNotFoundException("Job not found");
        }

        return job;
    }

    private Resume getOwnedResume(Long id, Long userId) {

        Resume resume = resumeMapper.findById(id);

        if (resume == null || !userId.equals(resume.getUserId())) {
            throw new ResumeNotFoundException("Resume not found");
        }

        return resume;
    }

    static final int MAX_VERIFIED_WRITES = 10;

    record TailorFilterResult(
            List<TailorChange> accepted,
            List<RejectedTailorChange> rejected) {
    }

    TailorFilterResult filterSafeChanges(
            List<ResumeDocumentNode> nodes,
            List<TailorChange> proposed,
            TailorMode mode) {

        Map<String, ResumeDocumentNode> nodesById = new LinkedHashMap<>();
        for (ResumeDocumentNode node : nodes) {
            nodesById.put(node.getNodeId(), node);
        }

        Set<String> allowedIds = ResumeTailorNodeGuard.allowedNodeIds(nodes);
        Set<String> acceptedNodeIds = new LinkedHashSet<>();
        List<TailorChange> accepted = new ArrayList<>();
        List<RejectedTailorChange> rejected = new ArrayList<>();

        if (proposed == null) {
            return new TailorFilterResult(accepted, rejected);
        }

        double maxLengthRatio = mode == TailorMode.AGGRESSIVE ? 1.5 : 1.25;
        int verifiedWriteCount = 0;

        for (TailorChange change : proposed) {
            if (change == null
                    || isBlank(change.getNodeId())
                    || isBlank(change.getNewText())) {
                rejected.add(new RejectedTailorChange(
                        change == null ? null : change.getNodeId(),
                        RejectedTailorChange.EMPTY_REQUIRED_FIELD
                ));
                continue;
            }

            ResumeDocumentNode node = nodesById.get(change.getNodeId());
            if (node == null) {
                rejected.add(new RejectedTailorChange(
                        change.getNodeId(),
                        RejectedTailorChange.UNKNOWN_NODE_ID
                ));
                continue;
            }

            if (!allowedIds.contains(node.getNodeId())) {
                rejected.add(new RejectedTailorChange(
                        change.getNodeId(),
                        RejectedTailorChange.NODE_NOT_ALLOWED
                ));
                continue;
            }

            if (!normalizeNodeText(node.getText())
                    .equals(normalizeNodeText(change.getOriginalText()))) {
                rejected.add(new RejectedTailorChange(
                        change.getNodeId(),
                        RejectedTailorChange.ORIGINAL_TEXT_MISMATCH
                ));
                continue;
            }

            if (normalizeNodeText(node.getText())
                    .equals(normalizeNodeText(change.getNewText()))) {
                rejected.add(new RejectedTailorChange(
                        change.getNodeId(),
                        RejectedTailorChange.TEXT_UNCHANGED
                ));
                continue;
            }

            if (exceedsLengthLimit(change.getOriginalText(), change.getNewText(), maxLengthRatio)) {
                rejected.add(new RejectedTailorChange(
                        change.getNodeId(),
                        RejectedTailorChange.TEXT_TOO_LONG
                ));
                continue;
            }

            String evidenceType = parseEvidenceType(change.getEvidenceType());
            if (evidenceType == null) {
                rejected.add(new RejectedTailorChange(
                        change.getNodeId(),
                        RejectedTailorChange.UNKNOWN_EVIDENCE_TYPE
                ));
                continue;
            }

            boolean inferred = TailorChange.EVIDENCE_INFERRED.equals(evidenceType);
            if (inferred && mode != TailorMode.AGGRESSIVE) {
                rejected.add(new RejectedTailorChange(
                        change.getNodeId(),
                        RejectedTailorChange.INFERRED_NOT_ALLOWED
                ));
                continue;
            }

            if (acceptedNodeIds.contains(change.getNodeId())) {
                rejected.add(new RejectedTailorChange(
                        change.getNodeId(),
                        RejectedTailorChange.DUPLICATE_NODE_ID
                ));
                continue;
            }

            if (!inferred && verifiedWriteCount >= MAX_VERIFIED_WRITES) {
                rejected.add(new RejectedTailorChange(
                        change.getNodeId(),
                        RejectedTailorChange.MAX_CHANGE_LIMIT_EXCEEDED
                ));
                continue;
            }

            change.setEvidenceType(evidenceType);
            change.setRequiresConfirmation(inferred);
            change.setApplied(!inferred);
            if (!inferred) {
                change.setInferredSkill(null);
                verifiedWriteCount++;
            }

            acceptedNodeIds.add(change.getNodeId());
            accepted.add(change);
        }

        return new TailorFilterResult(accepted, rejected);
    }

    private String parseEvidenceType(String evidenceType) {
        if (TailorChange.EVIDENCE_VERIFIED.equalsIgnoreCase(evidenceType)) {
            return TailorChange.EVIDENCE_VERIFIED;
        }
        if (TailorChange.EVIDENCE_INFERRED.equalsIgnoreCase(evidenceType)) {
            return TailorChange.EVIDENCE_INFERRED;
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean exceedsLengthLimit(
            String originalText,
            String newText,
            double maxRatio) {

        int originalLength = originalText == null ? 0 : originalText.strip().length();
        int newLength = newText == null ? 0 : newText.strip().length();
        if (originalLength == 0) {
            return false;
        }
        return newLength > Math.ceil(originalLength * maxRatio);
    }

    private String normalizeNodeText(String text) {
        if (text == null) {
            return "";
        }

        return text.replace('\u00A0', ' ')
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {

        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private JobResponse toJobResponse(Job job) {

        return new JobResponse(
                job.getId(),
                job.getCompany(),
                job.getTitle(),
                job.getLocation(),
                job.getJobUrl(),
                job.getDescription(),
                job.getSource(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
