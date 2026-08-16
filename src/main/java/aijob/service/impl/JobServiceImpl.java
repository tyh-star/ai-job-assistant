package aijob.service.impl;

import aijob.client.GreenhouseJobClient;
import aijob.dto.CreateJobRequest;
import aijob.dto.ImportJobsResponse;
import aijob.dto.JobMatchResponse;
import aijob.dto.JobResponse;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.UpdateJobRequest;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.TailorProposal;
import aijob.entity.User;
import aijob.enums.TailorMode;
import aijob.exception.InvalidResumeFileException;
import aijob.exception.InvalidTailorProposalException;
import aijob.exception.JobNotFoundException;
import aijob.exception.ResumeNotFoundException;
import aijob.exception.ResumeTextNotAvailableException;
import aijob.exception.TailorProposalConflictException;
import aijob.exception.TailorProposalNotFoundException;
import aijob.exception.TailoredFileNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.JobService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import aijob.util.GreenhouseBoardToken;
import aijob.util.HtmlTextExtractor;
import aijob.util.ResumeTailorNodeGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class JobServiceImpl implements JobService {

    private static final String SOURCE_MANUAL = "MANUAL";
    private static final String SOURCE_GREENHOUSE = "GREENHOUSE";
    private static final String STATUS_SAVED = "SAVED";
    static final int MAX_GREENHOUSE_IMPORTS = 50;
    private static final int PROPOSAL_TTL_HOURS = 24;
    private static final Path TAILORED_ROOT = Path.of("uploads", "tailored");
    private static final Pattern TAILORED_FILE_NAME = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.docx$"
    );
    private static final List<String> UNSUPPORTED_CLAIM_PHRASES = List.of(
            "high availability",
            "highly available",
            "scalability",
            "resilient infrastructure",
            "docker",
            "kubernetes"
    );

    private final JobMapper jobMapper;
    private final UserMapper userMapper;
    private final ResumeMapper resumeMapper;
    private final TailorProposalMapper tailorProposalMapper;
    private final AiResumeService aiResumeService;
    private final ResumeDocumentService resumeDocumentService;
    private final ResumeDocxTailorService resumeDocxTailorService;
    private final ObjectMapper objectMapper;
    private final GreenhouseJobClient greenhouseJobClient;

    public JobServiceImpl(JobMapper jobMapper,
                          UserMapper userMapper,
                          ResumeMapper resumeMapper,
                          TailorProposalMapper tailorProposalMapper,
                          AiResumeService aiResumeService,
                          ResumeDocumentService resumeDocumentService,
                          ResumeDocxTailorService resumeDocxTailorService,
                          ObjectMapper objectMapper,
                          GreenhouseJobClient greenhouseJobClient) {
        this.jobMapper = jobMapper;
        this.userMapper = userMapper;
        this.resumeMapper = resumeMapper;
        this.tailorProposalMapper = tailorProposalMapper;
        this.aiResumeService = aiResumeService;
        this.resumeDocumentService = resumeDocumentService;
        this.resumeDocxTailorService = resumeDocxTailorService;
        this.objectMapper = objectMapper;
        this.greenhouseJobClient = greenhouseJobClient;
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

        if (resolvedMode == TailorMode.AGGRESSIVE) {
            return createAggressiveProposal(
                    user.getId(),
                    resume.getId(),
                    job.getId(),
                    accepted,
                    rejected
            );
        }

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

        return toTailoredResponse(
                resume.getId(),
                job.getId(),
                resolvedMode,
                generatedPath.getFileName().toString(),
                generatedPath.toString(),
                accepted,
                appliedChangeCount,
                rejected,
                null,
                null,
                null
        );
    }

    @Override
    public TailoredResumeResponse getTailorProposal(
            String email,
            Long jobId,
            Long proposalId) {

        User user = getUserByEmail(email);
        getOwnedJob(jobId, user.getId());
        TailorProposal proposal = getVisibleProposal(proposalId, user.getId(), jobId);
        if (TailorProposal.STATUS_CONFIRMED.equals(proposal.getStatus())
                || isExpired(proposal)) {
            throw new TailorProposalNotFoundException("Tailor proposal not found");
        }
        return toProposalResponse(proposal, 0, proposalRejected(proposal), null, null);
    }

    @Override
    public TailoredResumeResponse confirmTailorProposal(
            String email,
            Long jobId,
            Long proposalId,
            List<String> selectedNodeIds) {

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (selectedNodeIds != null) {
            for (String nodeId : selectedNodeIds) {
                if (!isBlank(nodeId)) {
                    selected.add(nodeId);
                }
            }
        }
        if (selected.isEmpty()) {
            throw new InvalidTailorProposalException("selectedNodeIds must not be empty");
        }

        User user = getUserByEmail(email);
        getOwnedJob(jobId, user.getId());

        int claimed = tailorProposalMapper.claimPending(proposalId, user.getId(), jobId);
        if (claimed == 0) {
            throwConfirmUnavailable(proposalId, user.getId(), jobId);
        }

        boolean completed = false;
        try {
            TailorProposal proposal = tailorProposalMapper.findById(proposalId);
            if (proposal == null) {
                throw new TailorProposalNotFoundException("Tailor proposal not found");
            }

            Resume resume = getOwnedResume(proposal.getResumeId(), user.getId());
            if (!"docx".equalsIgnoreCase(resume.getFileType())) {
                throw new InvalidResumeFileException(
                        "Resume tailoring currently supports DOCX only"
                );
            }

            Path originalPath = Path.of(resume.getFilePath());
            if (!Files.exists(originalPath)) {
                throw new InvalidResumeFileException("Resume file not found");
            }

            List<ResumeDocumentNode> nodes =
                    resumeDocumentService.parseDocx(originalPath);
            List<TailorChange> stored = readChanges(proposal.getChangesJson());
            TailorFilterResult selectedResult = selectConfirmedChanges(nodes, stored, selected);

            Path generatedPath = resumeDocxTailorService.createTailoredDocx(
                    originalPath,
                    selectedResult.accepted(),
                    user.getId(),
                    jobId
            );

            int marked = tailorProposalMapper.markConfirmed(
                    proposalId,
                    generatedPath.getFileName().toString(),
                    generatedPath.toString()
            );
            if (marked == 0) {
                throw new IllegalStateException("Failed to confirm tailor proposal");
            }

            completed = true;
            proposal.setStatus(TailorProposal.STATUS_CONFIRMED);
            proposal.setGeneratedFileName(generatedPath.getFileName().toString());
            proposal.setGeneratedFilePath(generatedPath.toString());

            List<TailorChange> responseChanges = new ArrayList<>();
            for (TailorChange change : stored) {
                boolean written = selectedResult.accepted().stream()
                        .anyMatch(item -> item.getNodeId().equals(change.getNodeId()));
                change.setApplied(written);
                if (written) {
                    change.setRequiresConfirmation(false);
                }
                responseChanges.add(change);
            }

            return toTailoredResponse(
                    resume.getId(),
                    jobId,
                    TailorMode.AGGRESSIVE,
                    generatedPath.getFileName().toString(),
                    generatedPath.toString(),
                    responseChanges,
                    selectedResult.accepted().size(),
                    selectedResult.rejected(),
                    proposal.getId(),
                    TailorProposal.STATUS_CONFIRMED,
                    proposal.getExpiresAt()
            );
        } finally {
            if (!completed) {
                tailorProposalMapper.restorePending(proposalId);
            }
        }
    }

    @Override
    public Path downloadTailoredResume(
            String email,
            Long jobId,
            String fileName) {

        User user = getUserByEmail(email);
        getOwnedJob(jobId, user.getId());

        if (!isSafeTailoredFileName(fileName)) {
            throw new TailoredFileNotFoundException("Tailored file not found");
        }

        Path jobDirectory = TAILORED_ROOT
                .resolve(String.valueOf(user.getId()))
                .resolve(String.valueOf(jobId))
                .toAbsolutePath()
                .normalize();
        Path resolved = jobDirectory.resolve(fileName).normalize();

        if (!resolved.startsWith(jobDirectory)
                || !jobDirectory.equals(resolved.getParent())
                || !Files.isRegularFile(resolved)) {
            throw new TailoredFileNotFoundException("Tailored file not found");
        }

        return resolved;
    }

    @Override
    public ImportJobsResponse importGreenhouseJobs(String email, String boardToken) {
        User user = getUserByEmail(email);
        String token = GreenhouseBoardToken.parse(boardToken);
        GreenhouseJobClient.GreenhouseBoardSnapshot snapshot =
                greenhouseJobClient.fetchBoard(token);

        String company = trimToNull(snapshot.companyName());
        if (company == null) {
            company = token;
        }

        List<Job> existing = jobMapper.findByUserId(user.getId());
        if (existing == null) {
            existing = List.of();
        }

        Set<String> greenhouseIds = new HashSet<>();
        Set<String> jobUrls = new HashSet<>();
        for (Job job : existing) {
            if (SOURCE_GREENHOUSE.equals(job.getSource())) {
                String existingExternalId = trimToNull(job.getExternalJobId());
                if (existingExternalId != null) {
                    greenhouseIds.add(existingExternalId);
                }
            }
            String existingUrl = trimToNull(job.getJobUrl());
            if (existingUrl != null) {
                jobUrls.add(existingUrl);
            }
        }

        int importedCount = 0;
        int skippedDuplicateCount = 0;
        int skippedNoTitleCount = 0;
        int skippedNoUrlCount = 0;
        int skippedNoDescriptionCount = 0;
        int skippedOverLimitCount = 0;
        List<JobResponse> importedJobs = new ArrayList<>();

        List<GreenhouseJobClient.GreenhouseJobPosting> postings =
                snapshot.jobs() == null ? List.of() : snapshot.jobs();

        for (GreenhouseJobClient.GreenhouseJobPosting posting : postings) {
            if (posting == null) {
                skippedNoTitleCount++;
                continue;
            }

            String title = trimToNull(posting.title());
            if (title == null) {
                skippedNoTitleCount++;
                continue;
            }

            String jobUrl = trimToNull(posting.jobUrl());
            if (jobUrl == null) {
                skippedNoUrlCount++;
                continue;
            }

            String description = HtmlTextExtractor.toPlainText(posting.htmlContent());
            if (description.isBlank()) {
                skippedNoDescriptionCount++;
                continue;
            }

            String externalJobId = trimToNull(posting.externalJobId());
            boolean duplicate = (externalJobId != null && greenhouseIds.contains(externalJobId))
                    || jobUrls.contains(jobUrl);
            if (duplicate) {
                skippedDuplicateCount++;
                continue;
            }

            if (importedCount >= MAX_GREENHOUSE_IMPORTS) {
                skippedOverLimitCount++;
                continue;
            }

            Job job = new Job();
            job.setUserId(user.getId());
            job.setCompany(company);
            job.setTitle(title);
            job.setLocation(trimToNull(posting.location()));
            job.setJobUrl(jobUrl);
            job.setDescription(description);
            job.setSource(SOURCE_GREENHOUSE);
            job.setStatus(STATUS_SAVED);
            job.setExternalJobId(externalJobId);

            jobMapper.insert(job);
            Job saved = jobMapper.findById(job.getId());
            importedJobs.add(toJobResponse(saved != null ? saved : job));
            importedCount++;

            if (externalJobId != null) {
                greenhouseIds.add(externalJobId);
            }
            jobUrls.add(jobUrl);
        }

        return new ImportJobsResponse(
                importedCount,
                skippedDuplicateCount,
                skippedNoTitleCount,
                skippedNoUrlCount,
                skippedNoDescriptionCount,
                skippedOverLimitCount,
                importedJobs
        );
    }

    private boolean isSafeTailoredFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        if (fileName.contains("/")
                || fileName.contains("\\")
                || fileName.contains("..")) {
            return false;
        }
        return TAILORED_FILE_NAME.matcher(fileName).matches();
    }

    private TailoredResumeResponse createAggressiveProposal(
            Long userId,
            Long resumeId,
            Long jobId,
            List<TailorChange> accepted,
            List<RejectedTailorChange> rejected) {

        for (TailorChange change : accepted) {
            change.setApplied(false);
        }

        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(PROPOSAL_TTL_HOURS);
        TailorProposal proposal = new TailorProposal();
        proposal.setUserId(userId);
        proposal.setResumeId(resumeId);
        proposal.setJobId(jobId);
        proposal.setMode(TailorMode.AGGRESSIVE.name());
        proposal.setStatus(TailorProposal.STATUS_PENDING);
        proposal.setExpiresAt(expiresAt);
        proposal.setChangesJson(writeJson(accepted));
        proposal.setRejectedJson(writeJson(rejected));
        tailorProposalMapper.insert(proposal);

        return toTailoredResponse(
                resumeId,
                jobId,
                TailorMode.AGGRESSIVE,
                null,
                null,
                accepted,
                0,
                rejected,
                proposal.getId(),
                TailorProposal.STATUS_PENDING,
                expiresAt
        );
    }

    private TailorFilterResult selectConfirmedChanges(
            List<ResumeDocumentNode> nodes,
            List<TailorChange> stored,
            Set<String> selectedNodeIds) {

        Map<String, ResumeDocumentNode> nodesById = new LinkedHashMap<>();
        for (ResumeDocumentNode node : nodes) {
            nodesById.put(node.getNodeId(), node);
        }
        Set<String> allowedIds = ResumeTailorNodeGuard.allowedNodeIds(nodes);

        List<TailorChange> accepted = new ArrayList<>();
        List<RejectedTailorChange> rejected = new ArrayList<>();
        Set<String> acceptedNodeIds = new LinkedHashSet<>();

        for (TailorChange change : stored) {
            if (change == null || !selectedNodeIds.contains(change.getNodeId())) {
                continue;
            }
            if (acceptedNodeIds.contains(change.getNodeId())) {
                continue;
            }

            String reason = validateConfirmChange(change, nodesById, allowedIds);
            if (reason != null) {
                rejected.add(new RejectedTailorChange(change.getNodeId(), reason));
                continue;
            }

            change.setApplied(true);
            change.setRequiresConfirmation(false);
            acceptedNodeIds.add(change.getNodeId());
            accepted.add(change);
        }

        return new TailorFilterResult(accepted, rejected);
    }

    private String validateConfirmChange(
            TailorChange change,
            Map<String, ResumeDocumentNode> nodesById,
            Set<String> allowedIds) {

        if (isBlank(change.getNodeId()) || isBlank(change.getNewText())) {
            return RejectedTailorChange.EMPTY_REQUIRED_FIELD;
        }

        ResumeDocumentNode node = nodesById.get(change.getNodeId());
        if (node == null) {
            return RejectedTailorChange.UNKNOWN_NODE_ID;
        }
        if (!allowedIds.contains(node.getNodeId())) {
            return RejectedTailorChange.NODE_NOT_ALLOWED;
        }
        if (!normalizeNodeText(node.getText())
                .equals(normalizeNodeText(change.getOriginalText()))) {
            return RejectedTailorChange.ORIGINAL_TEXT_MISMATCH;
        }

        String evidenceType = parseEvidenceType(change.getEvidenceType());
        if (evidenceType == null) {
            return RejectedTailorChange.UNKNOWN_EVIDENCE_TYPE;
        }
        if (TailorChange.EVIDENCE_VERIFIED.equals(evidenceType)) {
            return validateVerifiedEvidence(change, nodesById);
        }
        return null;
    }

    private void throwConfirmUnavailable(Long proposalId, Long userId, Long jobId) {
        TailorProposal proposal = tailorProposalMapper.findById(proposalId);
        if (proposal != null
                && userId.equals(proposal.getUserId())
                && jobId.equals(proposal.getJobId())
                && TailorProposal.STATUS_CONFIRMING.equals(proposal.getStatus())
                && !isExpired(proposal)) {
            throw new TailorProposalConflictException(
                    "Tailor proposal is already being confirmed"
            );
        }
        throw new TailorProposalNotFoundException("Tailor proposal not found");
    }

    private TailorProposal getVisibleProposal(Long proposalId, Long userId, Long jobId) {
        TailorProposal proposal = tailorProposalMapper.findById(proposalId);
        if (proposal == null
                || !userId.equals(proposal.getUserId())
                || !jobId.equals(proposal.getJobId())) {
            throw new TailorProposalNotFoundException("Tailor proposal not found");
        }
        return proposal;
    }

    private boolean isExpired(TailorProposal proposal) {
        return proposal.getExpiresAt() == null
                || !proposal.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }

    private List<TailorChange> readChanges(String json) {
        return readJson(json, new TypeReference<>() {
        });
    }

    private List<RejectedTailorChange> proposalRejected(TailorProposal proposal) {
        return readJson(proposal.getRejectedJson(), new TypeReference<>() {
        });
    }

    private TailoredResumeResponse toProposalResponse(
            TailorProposal proposal,
            int appliedChangeCount,
            List<RejectedTailorChange> rejected,
            String generatedFileName,
            String generatedFilePath) {

        List<TailorChange> changes = readChanges(proposal.getChangesJson());
        for (TailorChange change : changes) {
            if (appliedChangeCount == 0) {
                change.setApplied(false);
            }
        }
        return toTailoredResponse(
                proposal.getResumeId(),
                proposal.getJobId(),
                TailorMode.AGGRESSIVE,
                generatedFileName,
                generatedFilePath,
                changes,
                appliedChangeCount,
                rejected,
                proposal.getId(),
                proposal.getStatus(),
                proposal.getExpiresAt()
        );
    }

    private TailoredResumeResponse toTailoredResponse(
            Long resumeId,
            Long jobId,
            TailorMode mode,
            String generatedFileName,
            String generatedFilePath,
            List<TailorChange> changes,
            int appliedChangeCount,
            List<RejectedTailorChange> rejected,
            Long proposalId,
            String status,
            LocalDateTime expiresAt) {

        String downloadUrl = isBlank(generatedFileName)
                ? null
                : "/api/v1/jobs/" + jobId + "/tailored/" + generatedFileName;

        return new TailoredResumeResponse(
                resumeId,
                jobId,
                mode,
                generatedFileName,
                generatedFilePath,
                downloadUrl,
                changes,
                appliedChangeCount == 0,
                appliedChangeCount,
                rejected == null ? 0 : rejected.size(),
                rejected == null ? List.of() : rejected,
                proposalId,
                status,
                expiresAt
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize tailor proposal", ex);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            try {
                return objectMapper.readValue("[]", type);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Failed to deserialize tailor proposal", ex);
            }
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize tailor proposal", ex);
        }
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

            if (!inferred) {
                String evidenceReason = validateVerifiedEvidence(change, nodesById);
                if (evidenceReason != null) {
                    rejected.add(new RejectedTailorChange(change.getNodeId(), evidenceReason));
                    continue;
                }
                if (mode == TailorMode.CONSERVATIVE
                        && hasUnsupportedClaim(change.getNewText(), nodes)) {
                    rejected.add(new RejectedTailorChange(
                            change.getNodeId(),
                            RejectedTailorChange.UNSUPPORTED_CLAIM
                    ));
                    continue;
                }
            }

            if (acceptedNodeIds.contains(change.getNodeId())) {
                rejected.add(new RejectedTailorChange(
                        change.getNodeId(),
                        RejectedTailorChange.DUPLICATE_NODE_ID
                ));
                continue;
            }

            if (accepted.size() >= MAX_VERIFIED_WRITES) {
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

    private String validateVerifiedEvidence(
            TailorChange change,
            Map<String, ResumeDocumentNode> nodesById) {

        List<String> evidenceNodeIds = change.getEvidenceNodeIds();
        if (evidenceNodeIds == null
                || evidenceNodeIds.isEmpty()
                || isBlank(change.getEvidenceQuote())) {
            return RejectedTailorChange.MISSING_EVIDENCE;
        }

        StringBuilder citedText = new StringBuilder();
        for (String evidenceNodeId : evidenceNodeIds) {
            if (isBlank(evidenceNodeId) || !nodesById.containsKey(evidenceNodeId)) {
                return RejectedTailorChange.UNKNOWN_EVIDENCE_NODE_ID;
            }
            citedText.append(' ').append(nodesById.get(evidenceNodeId).getText());
        }

        String cited = normalizeNodeText(citedText.toString());
        String quote = normalizeNodeText(change.getEvidenceQuote());
        if (quote.isEmpty() || !cited.contains(quote)) {
            return RejectedTailorChange.EVIDENCE_QUOTE_MISMATCH;
        }
        return null;
    }

    private boolean hasUnsupportedClaim(
            String newText,
            List<ResumeDocumentNode> nodes) {

        String rewritten = normalizeNodeText(newText);
        StringBuilder resumeText = new StringBuilder();
        for (ResumeDocumentNode node : nodes) {
            resumeText.append(' ').append(node.getText());
        }
        String resume = normalizeNodeText(resumeText.toString());

        for (String phrase : UNSUPPORTED_CLAIM_PHRASES) {
            if (containsClaimPhrase(rewritten, phrase)
                    && !containsClaimPhrase(resume, phrase)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsClaimPhrase(String haystack, String phrase) {
        if (phrase.contains(" ")) {
            return haystack.contains(phrase);
        }
        return Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b")
                .matcher(haystack)
                .find();
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
                job.getExternalJobId(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
