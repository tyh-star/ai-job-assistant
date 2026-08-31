package aijob.service.impl;

import aijob.dto.EntryOption;
import aijob.dto.EvidenceQuestion;
import aijob.dto.JobRequirementDraft;
import aijob.dto.RequirementPriority;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.dto.TailorAnalysisDimensions;
import aijob.dto.TailorAnalysisRequirement;
import aijob.dto.TailorAnalysisResponse;
import aijob.entity.Job;
import aijob.entity.JobRequirementProfile;
import aijob.entity.Resume;
import aijob.entity.ResumeEvidenceItem;
import aijob.entity.User;
import aijob.exception.JobNotFoundException;
import aijob.exception.ResumeTextNotAvailableException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.JobMapper;
import aijob.mapper.JobRequirementProfileMapper;
import aijob.mapper.ResumeEvidenceMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.JobRequirementExtractor;
import aijob.service.ResumeDocumentService;
import aijob.service.TailorAnalysisService;
import aijob.util.EvidenceAvailabilityMapper;
import aijob.util.EvidenceQuestionGenerator;
import aijob.util.EvidenceText;
import aijob.util.JobDescriptionHash;
import aijob.util.RequirementAtomizer;
import aijob.util.RequirementProfileIds;
import aijob.util.RequirementTaxonomySanitizer;
import aijob.util.ResumeEntryCatalog;
import aijob.util.ResumeAnalysisInput;
import aijob.util.ResumeAnalysisInputFactory;
import aijob.util.ResumeFamily;
import aijob.util.ResumeRequirementMatcher;
import aijob.util.TailorAnalysisExplainer;
import aijob.util.TailorAnalysisScorer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class TailorAnalysisServiceImpl implements TailorAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TailorAnalysisServiceImpl.class);
    private static final int MAX_REQUIREMENTS = 20;
    private static final Pattern MUST_LANGUAGE = Pattern.compile(
            "(?i)\\b(required|must(?:-|\\s)?have|must be|qualifications|responsibilities|minimum)\\b"
    );
    private static final TypeReference<List<JobRequirementDraft>> DRAFT_LIST =
            new TypeReference<>() {
            };

    private final UserMapper userMapper;
    private final JobMapper jobMapper;
    private final ResumeMapper resumeMapper;
    private final ResumeEvidenceMapper resumeEvidenceMapper;
    private final ResumeDocumentService resumeDocumentService;
    private final JobRequirementExtractor jobRequirementExtractor;
    private final JobRequirementProfileMapper jobRequirementProfileMapper;
    private final ObjectMapper objectMapper;
    private final ResumeAnalysisInputFactory analysisInputFactory;
    private final String extractionModel;
    private final ConcurrentHashMap<Long, Object> jobLocks = new ConcurrentHashMap<>();

    public TailorAnalysisServiceImpl(
            UserMapper userMapper,
            JobMapper jobMapper,
            ResumeMapper resumeMapper,
            ResumeEvidenceMapper resumeEvidenceMapper,
            ResumeDocumentService resumeDocumentService,
            JobRequirementExtractor jobRequirementExtractor,
            JobRequirementProfileMapper jobRequirementProfileMapper,
            ObjectMapper objectMapper,
            ResumeAnalysisInputFactory analysisInputFactory,
            @Value("${openai.model}") String extractionModel) {
        this.userMapper = userMapper;
        this.jobMapper = jobMapper;
        this.resumeMapper = resumeMapper;
        this.resumeEvidenceMapper = resumeEvidenceMapper;
        this.resumeDocumentService = resumeDocumentService;
        this.jobRequirementExtractor = jobRequirementExtractor;
        this.jobRequirementProfileMapper = jobRequirementProfileMapper;
        this.objectMapper = objectMapper;
        this.analysisInputFactory = analysisInputFactory;
        this.extractionModel = extractionModel;
    }

    @Override
    public TailorAnalysisResponse analyze(String email, Long jobId, Long resumeId) {
        return analyze(email, jobId, resumeId, false);
    }

    @Override
    public TailorAnalysisResponse refreshProfile(String email, Long jobId, Long resumeId) {
        return analyze(email, jobId, resumeId, true);
    }

    private TailorAnalysisResponse analyze(String email, Long jobId, Long resumeId, boolean forceRefresh) {
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

        ResumeAnalysisInput input = analysisInputFactory.resolve(resume, user.getId());
        List<ResumeDocumentNode> nodes = input.matchingNodes();
        ResumeStructure structure = input.structuredResume();
        RequirementSnapshot snapshot = resolveRequirements(user.getId(), job, forceRefresh);
        List<TailorAnalysisRequirement> requirements = ResumeRequirementMatcher.match(
                snapshot.drafts(), nodes, structure);
        for (TailorAnalysisRequirement requirement : requirements) {
            ResumeRequirementMatcher.sanitizeEvidence(requirement, input);
        }

        TailorAnalysisDimensions dimensions = TailorAnalysisScorer.scoreDimensions(requirements, nodes);
        int overall = TailorAnalysisScorer.overallScore(dimensions);

        List<ResumeEvidenceItem> bank = resumeEvidenceMapper.findByResumeAndUser(
                input.evidenceRootId(), user.getId());
        if (bank == null) {
            bank = List.of();
        }
        List<EntryOption> entries = ResumeEntryCatalog.options(nodes, structure);
        EvidenceAvailabilityMapper.apply(requirements, bank);
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(requirements, bank, entries);

        TailorAnalysisResponse response = new TailorAnalysisResponse();
        response.setResumeId(resume.getId());
        response.setJobId(job.getId());
        response.setDimensions(dimensions);
        response.setOverallScore(overall);
        response.setScoreBand(TailorAnalysisScorer.scoreBand(overall));
        response.setScoreExplanation(TailorAnalysisExplainer.explain(requirements, questions));
        response.setRequirements(requirements);
        response.setEvidenceQuestions(questions);
        response.setSummary(TailorAnalysisScorer.summary(requirements));
        response.setRequirementsFixed(true);
        response.setProfileUpdatedAt(formatProfileUpdatedAt(snapshot.updatedAt()));
        return response;
    }

    private RequirementSnapshot resolveRequirements(Long userId, Job job, boolean forceRefresh) {
        synchronized (lockFor(job.getId())) {
            String canonical = JobDescriptionHash.canonicalize(job.getDescription());
            String hash = JobDescriptionHash.sha256(canonical);
            int schemaVersion = RequirementProfileIds.SCHEMA_VERSION;
            if (!forceRefresh) {
                RequirementSnapshot cached = readValidCache(userId, job.getId(), hash, schemaVersion);
                if (cached != null) {
                    return cached;
                }
            }
            log.info(
                    "Extracting job requirements jobId={} schemaVersion={} forceRefresh={}",
                    job.getId(),
                    schemaVersion,
                    forceRefresh
            );
            List<JobRequirementDraft> drafts = RequirementAtomizer.atomize(sanitizeDrafts(
                    jobRequirementExtractor.extract(canonical),
                    canonical
            ));
            RequirementTaxonomySanitizer.apply(drafts);
            JobRequirementProfile saved = persistProfile(
                    userId, job.getId(), hash, schemaVersion, drafts);
            return new RequirementSnapshot(drafts, saved.getUpdatedAt());
        }
    }

    private RequirementSnapshot readValidCache(
            Long userId,
            Long jobId,
            String hash,
            int schemaVersion) {
        JobRequirementProfile cached = jobRequirementProfileMapper.findByUserAndJob(userId, jobId);
        if (cached == null) {
            return null;
        }
        if (cached.getSchemaVersion() == null || cached.getSchemaVersion() != schemaVersion) {
            return null;
        }
        if (cached.getDescriptionSha256() == null || !cached.getDescriptionSha256().equals(hash)) {
            return null;
        }
        List<JobRequirementDraft> drafts = parseCachedDrafts(cached);
        if (drafts == null) {
            return null;
        }
        return new RequirementSnapshot(drafts, cached.getUpdatedAt());
    }

    private List<JobRequirementDraft> parseCachedDrafts(JobRequirementProfile profile) {
        try {
            List<JobRequirementDraft> drafts = objectMapper.readValue(
                    profile.getRequirementsJson(),
                    DRAFT_LIST
            );
            return drafts == null ? List.of() : drafts;
        } catch (Exception ex) {
            log.warn(
                    "Job requirement profile JSON is unreadable profileId={} schemaVersion={} cause={}",
                    profile.getId(),
                    profile.getSchemaVersion(),
                    ex.getClass().getSimpleName()
            );
            return null;
        }
    }

    private JobRequirementProfile persistProfile(
            Long userId,
            Long jobId,
            String hash,
            int schemaVersion,
            List<JobRequirementDraft> drafts) {
        JobRequirementProfile profile = new JobRequirementProfile();
        profile.setUserId(userId);
        profile.setJobId(jobId);
        profile.setDescriptionSha256(hash);
        profile.setSchemaVersion(schemaVersion);
        profile.setExtractionModel(extractionModel);
        profile.setRequirementsJson(writeDraftsJson(drafts));
        try {
            jobRequirementProfileMapper.upsert(profile);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Job requirement profile upsert raced userId={} jobId={}", userId, jobId);
            try {
                jobRequirementProfileMapper.upsert(profile);
            } catch (DataIntegrityViolationException retry) {
                log.warn("Job requirement profile upsert retry raced userId={} jobId={}", userId, jobId);
            }
        }
        JobRequirementProfile saved = jobRequirementProfileMapper.findByUserAndJob(userId, jobId);
        if (saved != null) {
            return saved;
        }
        profile.setUpdatedAt(LocalDateTime.now());
        return profile;
    }

    private String writeDraftsJson(List<JobRequirementDraft> drafts) {
        try {
            return objectMapper.writeValueAsString(drafts);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize job requirement profile", ex);
        }
    }

    private Object lockFor(Long jobId) {
        return jobLocks.computeIfAbsent(jobId, ignored -> new Object());
    }

    private String formatProfileUpdatedAt(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return null;
        }
        return updatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private record RequirementSnapshot(List<JobRequirementDraft> drafts, LocalDateTime updatedAt) {
    }

    List<JobRequirementDraft> sanitizeDrafts(List<JobRequirementDraft> drafts, String jobDescription) {
        String jd = EvidenceText.normalize(jobDescription);
        boolean allowsMustHave = MUST_LANGUAGE.matcher(jobDescription == null ? "" : jobDescription).find();
        List<JobRequirementDraft> cleaned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (drafts == null) {
            return cleaned;
        }
        for (JobRequirementDraft draft : drafts) {
            if (draft == null || isBlank(draft.getRequirement())) {
                continue;
            }
            if (!jd.contains(EvidenceText.normalize(draft.getRequirement()))
                    && !hasAnyTermInJd(draft.getExactTerms(), jd)) {
                continue;
            }
            List<String> terms = keepTermsInJd(draft.getExactTerms(), jd);
            if (terms.isEmpty() && !jd.contains(EvidenceText.normalize(draft.getRequirement()))) {
                continue;
            }
            String key = EvidenceText.normalize(draft.getRequirement()) + "|" + String.join(",", terms);
            if (!seen.add(key)) {
                continue;
            }
            JobRequirementDraft copy = new JobRequirementDraft();
            copy.setRequirement(draft.getRequirement().strip());
            copy.setCategory(RequirementTaxonomySanitizer.classify(
                    draft.getRequirement(),
                    terms,
                    draft.getCategory()
            ));
            copy.setPriority(allowsMustHave ? draft.getPriority() : demoteMustHave(draft.getPriority()));
            copy.setExactTerms(terms);
            cleaned.add(copy);
            if (cleaned.size() >= MAX_REQUIREMENTS) {
                break;
            }
        }
        return cleaned;
    }

    private List<ResumeDocumentNode> loadEvidenceNodes(Resume resume, String resumeText) {
        if (resume.getFileType() != null
                && "docx".equalsIgnoreCase(resume.getFileType())
                && resume.getFilePath() != null) {
            Path path = Path.of(resume.getFilePath());
            if (Files.exists(path)) {
                try {
                    List<ResumeDocumentNode> parsed = resumeDocumentService.parseDocx(path);
                    if (parsed != null && !parsed.isEmpty()) {
                        return parsed;
                    }
                } catch (RuntimeException ignored) {
                    // Fall back to extracted text. Analysis should not depend on Writer.
                }
            }
        }
        return linesAsNodes(resumeText);
    }

    static List<ResumeDocumentNode> linesAsNodes(String resumeText) {
        List<ResumeDocumentNode> nodes = new ArrayList<>();
        int index = 0;
        for (String line : resumeText.lines().toList()) {
            if (line == null || line.isBlank()) {
                continue;
            }
            nodes.add(new ResumeDocumentNode("t-" + index++, "TEXT", line.strip()));
        }
        if (nodes.isEmpty()) {
            nodes.add(new ResumeDocumentNode("t-0", "TEXT", resumeText.strip()));
        }
        return nodes;
    }

    private List<String> keepTermsInJd(List<String> terms, String normalizedJd) {
        if (terms == null) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String term : terms) {
            if (isBlank(term)) {
                continue;
            }
            String normalized = EvidenceText.normalize(term);
            if (normalizedJd.contains(normalized)) {
                unique.putIfAbsent(normalized, term.strip());
            }
        }
        return new ArrayList<>(unique.values());
    }

    private boolean hasAnyTermInJd(List<String> terms, String normalizedJd) {
        if (terms == null) {
            return false;
        }
        for (String term : terms) {
            if (!isBlank(term) && normalizedJd.contains(EvidenceText.normalize(term))) {
                return true;
            }
        }
        return false;
    }

    private String demoteMustHave(String priority) {
        if (RequirementPriority.MUST_HAVE.equalsIgnoreCase(priority == null ? "" : priority.strip())) {
            return RequirementPriority.PREFERRED;
        }
        return priority;
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
        return ResumeFamily.requireOwned(resumeMapper, id, userId);
    }

    private ResumeStructure parseStructure(Resume resume) {
        if (resume.getStructuredJson() == null || resume.getStructuredJson().isBlank()) {
            return null;
        }
        try {
            ResumeStructure structure = objectMapper.readValue(resume.getStructuredJson(), ResumeStructure.class);
            if (structure != null) {
                structure.normalize();
            }
            return structure;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
