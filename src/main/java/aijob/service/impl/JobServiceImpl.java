package aijob.service.impl;

import aijob.client.GreenhouseJobClient;
import aijob.config.JSearchClientConfig;
import aijob.config.JobSearchProperties;
import aijob.config.StoragePathResolver;
import aijob.dto.BulkDeleteJobsResponse;
import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewriteCandidatesResponse;
import aijob.dto.ConservativeRewritePlan;
import aijob.dto.ConservativeRewriteRepair;
import aijob.dto.AnswerEnhancedQuestionRequest;
import aijob.dto.AnswerEnhancedQuestionResponse;
import aijob.dto.AnswerStatus;
import aijob.dto.ConfirmTailorProposalRequest;
import aijob.dto.CreateJobRequest;
import aijob.dto.EditedSkillReview;
import aijob.dto.EditedTailorChange;
import aijob.dto.EditedTailorEntry;
import aijob.dto.ImportJobsResponse;
import aijob.dto.JobMatchResponse;
import aijob.dto.JobResponse;
import aijob.dto.ImportSearchResultsRequest;
import aijob.dto.ImportSearchResultsResponse;
import aijob.dto.JobSearchRequest;
import aijob.dto.JobSearchResponse;
import aijob.dto.JobSearchResult;
import aijob.dto.SaveJobSearchResultRequest;
import aijob.dto.SaveJobSearchResultResponse;
import aijob.dto.SaveResumeEvidenceRequest;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeEvidenceItemResponse;
import aijob.dto.RewriteIntent;
import aijob.dto.TailorChange;
import aijob.dto.TailorProposalState;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.UpliftType;
import aijob.dto.UpdateJobRequest;
import aijob.dto.V3EntryReview;
import aijob.dto.V3ReviewNode;
import aijob.dto.V3SkillReview;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.TailorProposal;
import aijob.entity.User;
import aijob.enums.PdfExportStatus;
import aijob.enums.TailorMode;
import aijob.exception.InvalidBulkDeleteException;
import aijob.exception.InvalidEvidenceRequestException;
import aijob.exception.InvalidResumeFileException;
import aijob.exception.InvalidTailorProposalException;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobNotFoundException;
import aijob.exception.JobSearchPageConflictException;
import aijob.exception.JobSearchUnavailableException;
import aijob.jobsearch.ExternalJobIds;
import aijob.jobsearch.GreenhouseJobUrl;
import aijob.jobsearch.JobSearchCacheKeys;
import aijob.jobsearch.JobSearchCapability;
import aijob.jobsearch.JobSearchDeduper;
import aijob.jobsearch.ProviderJobHit;
import aijob.jobsearch.ProviderQueryCache;
import aijob.jobsearch.ProviderSearchPage;
import aijob.jobsearch.SearchSession;
import aijob.jobsearch.SearchSessionStore;
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
import aijob.service.JobSearchProvider;
import aijob.service.JobSearchProviderRegistry;
import aijob.service.JobService;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import aijob.service.ResumeEvidenceService;
import aijob.tailor.v3.TailorEngineRouter;
import aijob.tailor.v3.V3BasicRunResult;
import aijob.tailor.v3.V3BasicTailorService;
import aijob.tailor.v3.V3EnhancedAnswerBinder;
import aijob.tailor.v3.V3EnhancedOpportunityService;
import aijob.tailor.v3.V3EnhancedQuestion;
import aijob.tailor.v3.V3EnhancedRunResult;
import aijob.tailor.v3.V3EnhancedTailorService;
import aijob.tailor.v3.V3OpportunityMap;
import aijob.tailor.v3.V3UserEvidence;
import aijob.tailor.v3.V3UserEvidenceAdapter;
import aijob.util.ClaimLedger;
import aijob.util.ResumeAnalysisInput;
import aijob.util.ResumeAnalysisInputFactory;
import aijob.util.ConservativeBatchSelector;
import aijob.util.ConservativeCandidateHardGate;
import aijob.util.ConservativeCandidateIntent;
import aijob.util.ConservativeCandidateLocalScorer;
import aijob.util.ConservativeContinueSelector;
import aijob.util.ConservativeEvidencePacket;
import aijob.util.ConservativeLanguageJudge;
import aijob.util.ConservativeReservedTargetPolicy;
import aijob.util.OpportunityDetector;
import aijob.util.OpportunityResolutionGate;
import aijob.util.ConservativeRewriteReasons;
import aijob.util.ConservativeTailorPlanner;
import aijob.util.FinalResumeCoherenceValidator;
import aijob.util.GreenhouseBoardToken;
import aijob.util.HtmlTextExtractor;
import aijob.util.JobFingerprint;
import aijob.util.ResumeTailorNodeGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class JobServiceImpl implements JobService {

    private static final Logger log = LoggerFactory.getLogger(JobServiceImpl.class);
    private static final String SOURCE_MANUAL = "MANUAL";
    private static final String SOURCE_GREENHOUSE = "GREENHOUSE";
    private static final String SOURCE_ADZUNA = "ADZUNA";
    private static final String SOURCE_JSEARCH = "JSEARCH";
    private static final String STATUS_SAVED = "SAVED";
    static final int MAX_GREENHOUSE_IMPORTS = 50;
    static final int MAX_SEARCH_IMPORTS = 20;
    static final int DESCRIPTION_SNIPPET_CHARS = 280;
    private static final int PROPOSAL_TTL_HOURS = 24;
    private static final Pattern TAILORED_FILE_NAME = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(docx|pdf)$"
    );
    private static final List<String> UNSUPPORTED_CLAIM_PHRASES = List.of(
            "high availability",
            "highly available",
            "scalability",
            "resilient infrastructure",
            "docker",
            "kubernetes",
            "ci/cd",
            "cicd"
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
    private final PdfExportService pdfExportService;
    private final StoragePathResolver storagePathResolver;
    private final JobSearchProviderRegistry jobSearchProviderRegistry;
    private final ProviderQueryCache providerQueryCache;
    private final SearchSessionStore searchSessionStore;
    private final JobSearchProperties jobSearchProperties;
    private final TailorEngineRouter tailorEngineRouter;
    private final V3BasicTailorService v3BasicTailorService;
    private final ResumeAnalysisInputFactory resumeAnalysisInputFactory;
    private final V3EnhancedOpportunityService v3EnhancedOpportunityService;
    private final V3EnhancedTailorService v3EnhancedTailorService;
    private final ResumeEvidenceService resumeEvidenceService;
    private final ConcurrentHashMap<UUID, Object> searchSessionLocks = new ConcurrentHashMap<>();

    public JobServiceImpl(JobMapper jobMapper,
                          UserMapper userMapper,
                          ResumeMapper resumeMapper,
                          TailorProposalMapper tailorProposalMapper,
                          AiResumeService aiResumeService,
                          ResumeDocumentService resumeDocumentService,
                          ResumeDocxTailorService resumeDocxTailorService,
                          ObjectMapper objectMapper,
                          GreenhouseJobClient greenhouseJobClient,
                          PdfExportService pdfExportService,
                          StoragePathResolver storagePathResolver) {
        this(
                jobMapper,
                userMapper,
                resumeMapper,
                tailorProposalMapper,
                aiResumeService,
                resumeDocumentService,
                resumeDocxTailorService,
                objectMapper,
                greenhouseJobClient,
                pdfExportService,
                storagePathResolver,
                null,
                null,
                null
        );
    }

    public JobServiceImpl(JobMapper jobMapper,
                          UserMapper userMapper,
                          ResumeMapper resumeMapper,
                          TailorProposalMapper tailorProposalMapper,
                          AiResumeService aiResumeService,
                          ResumeDocumentService resumeDocumentService,
                          ResumeDocxTailorService resumeDocxTailorService,
                          ObjectMapper objectMapper,
                          GreenhouseJobClient greenhouseJobClient,
                          PdfExportService pdfExportService,
                          StoragePathResolver storagePathResolver,
                          JobSearchProviderRegistry jobSearchProviderRegistry,
                          ProviderQueryCache providerQueryCache,
                          SearchSessionStore searchSessionStore) {
        this(
                jobMapper,
                userMapper,
                resumeMapper,
                tailorProposalMapper,
                aiResumeService,
                resumeDocumentService,
                resumeDocxTailorService,
                objectMapper,
                greenhouseJobClient,
                pdfExportService,
                storagePathResolver,
                jobSearchProviderRegistry,
                providerQueryCache,
                searchSessionStore,
                JobSearchProperties.defaults()
        );
    }

    public JobServiceImpl(JobMapper jobMapper,
                          UserMapper userMapper,
                          ResumeMapper resumeMapper,
                          TailorProposalMapper tailorProposalMapper,
                          AiResumeService aiResumeService,
                          ResumeDocumentService resumeDocumentService,
                          ResumeDocxTailorService resumeDocxTailorService,
                          ObjectMapper objectMapper,
                          GreenhouseJobClient greenhouseJobClient,
                          PdfExportService pdfExportService,
                          StoragePathResolver storagePathResolver,
                          JobSearchProviderRegistry jobSearchProviderRegistry,
                          ProviderQueryCache providerQueryCache,
                          SearchSessionStore searchSessionStore,
                          JobSearchProperties jobSearchProperties) {
        this(
                jobMapper,
                userMapper,
                resumeMapper,
                tailorProposalMapper,
                aiResumeService,
                resumeDocumentService,
                resumeDocxTailorService,
                objectMapper,
                greenhouseJobClient,
                pdfExportService,
                storagePathResolver,
                jobSearchProviderRegistry,
                providerQueryCache,
                searchSessionStore,
                jobSearchProperties,
                null,
                null,
                null
        );
    }

    public JobServiceImpl(JobMapper jobMapper,
                          UserMapper userMapper,
                          ResumeMapper resumeMapper,
                          TailorProposalMapper tailorProposalMapper,
                          AiResumeService aiResumeService,
                          ResumeDocumentService resumeDocumentService,
                          ResumeDocxTailorService resumeDocxTailorService,
                          ObjectMapper objectMapper,
                          GreenhouseJobClient greenhouseJobClient,
                          PdfExportService pdfExportService,
                          StoragePathResolver storagePathResolver,
                          JobSearchProviderRegistry jobSearchProviderRegistry,
                          ProviderQueryCache providerQueryCache,
                          SearchSessionStore searchSessionStore,
                          JobSearchProperties jobSearchProperties,
                          TailorEngineRouter tailorEngineRouter,
                          V3BasicTailorService v3BasicTailorService,
                          ResumeAnalysisInputFactory resumeAnalysisInputFactory) {
        this(
                jobMapper,
                userMapper,
                resumeMapper,
                tailorProposalMapper,
                aiResumeService,
                resumeDocumentService,
                resumeDocxTailorService,
                objectMapper,
                greenhouseJobClient,
                pdfExportService,
                storagePathResolver,
                jobSearchProviderRegistry,
                providerQueryCache,
                searchSessionStore,
                jobSearchProperties,
                tailorEngineRouter,
                v3BasicTailorService,
                resumeAnalysisInputFactory,
                null,
                null,
                null
        );
    }

    @Autowired
    public JobServiceImpl(JobMapper jobMapper,
                          UserMapper userMapper,
                          ResumeMapper resumeMapper,
                          TailorProposalMapper tailorProposalMapper,
                          AiResumeService aiResumeService,
                          ResumeDocumentService resumeDocumentService,
                          ResumeDocxTailorService resumeDocxTailorService,
                          ObjectMapper objectMapper,
                          GreenhouseJobClient greenhouseJobClient,
                          PdfExportService pdfExportService,
                          StoragePathResolver storagePathResolver,
                          JobSearchProviderRegistry jobSearchProviderRegistry,
                          ProviderQueryCache providerQueryCache,
                          SearchSessionStore searchSessionStore,
                          JobSearchProperties jobSearchProperties,
                          TailorEngineRouter tailorEngineRouter,
                          V3BasicTailorService v3BasicTailorService,
                          ResumeAnalysisInputFactory resumeAnalysisInputFactory,
                          V3EnhancedOpportunityService v3EnhancedOpportunityService,
                          V3EnhancedTailorService v3EnhancedTailorService,
                          ResumeEvidenceService resumeEvidenceService) {
        this.jobMapper = jobMapper;
        this.userMapper = userMapper;
        this.resumeMapper = resumeMapper;
        this.tailorProposalMapper = tailorProposalMapper;
        this.aiResumeService = aiResumeService;
        this.resumeDocumentService = resumeDocumentService;
        this.resumeDocxTailorService = resumeDocxTailorService;
        this.objectMapper = objectMapper;
        this.greenhouseJobClient = greenhouseJobClient;
        this.pdfExportService = pdfExportService;
        this.storagePathResolver = storagePathResolver;
        this.jobSearchProviderRegistry = jobSearchProviderRegistry;
        this.providerQueryCache = providerQueryCache;
        this.searchSessionStore = searchSessionStore;
        this.jobSearchProperties = jobSearchProperties == null
                ? JobSearchProperties.defaults()
                : jobSearchProperties;
        this.tailorEngineRouter = tailorEngineRouter;
        this.v3BasicTailorService = v3BasicTailorService;
        this.resumeAnalysisInputFactory = resumeAnalysisInputFactory;
        this.v3EnhancedOpportunityService = v3EnhancedOpportunityService;
        this.v3EnhancedTailorService = v3EnhancedTailorService;
        this.resumeEvidenceService = resumeEvidenceService;
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
    public JobResponse markApplied(String email, Long id) {
        User user = getUserByEmail(email);
        Job job = getOwnedJob(id, user.getId());

        // Deliberately UNCONDITIONAL -- no "already applied?" check here. See
        // JobMapper#markApplied's javadoc: that UPDATE's own COALESCE is what
        // guarantees a repeat confirmation never resets appliedAt, so this method does
        // not need to (and must not) gate the write behind a Java-side read of the
        // current state, which would be a check-then-act race under concurrent
        // requests. This is the same call {@code JobCatalogService#markApplied} makes,
        // unconditionally, for the same reason -- one shared SQL statement is the
        // single source of truth for this idempotency, not duplicated Java logic in
        // each caller.
        LocalDateTime appliedAt = LocalDateTime.now(ZoneOffset.UTC);
        jobMapper.markApplied(id, user.getId(), appliedAt);

        Job updated = jobMapper.findById(id);
        return toJobResponse(updated != null ? updated : job);
    }

    @Override
    public JobResponse clearApplied(String email, Long id) {
        User user = getUserByEmail(email);
        getOwnedJob(id, user.getId());

        jobMapper.clearApplied(id, user.getId());

        Job updated = jobMapper.findById(id);
        return toJobResponse(updated != null ? updated : getOwnedJob(id, user.getId()));
    }

    @Override
    @Transactional
    public BulkDeleteJobsResponse bulkDeleteJobs(String email, List<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            throw new InvalidBulkDeleteException("Job IDs cannot be empty");
        }

        List<Long> uniqueIds = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Long jobId : jobIds) {
            if (jobId == null || !seen.add(jobId)) {
                continue;
            }
            uniqueIds.add(jobId);
        }
        if (uniqueIds.isEmpty()) {
            throw new InvalidBulkDeleteException("Job IDs cannot be empty");
        }

        User user = getUserByEmail(email);
        List<Job> jobs = new ArrayList<>();
        for (Long jobId : uniqueIds) {
            Job job = jobMapper.findById(jobId);
            if (job == null || !user.getId().equals(job.getUserId())) {
                throw new JobNotFoundException("Job not found");
            }
            jobs.add(job);
        }
        for (Job job : jobs) {
            if (!SOURCE_GREENHOUSE.equals(job.getSource())) {
                throw new InvalidBulkDeleteException(
                        "Bulk delete is only allowed for Greenhouse jobs");
            }
        }

        int deletedCount = 0;
        for (Job job : jobs) {
            int deleted = jobMapper.deleteByIdAndUserId(job.getId(), user.getId());
            if (deleted == 0) {
                throw new JobNotFoundException("Job not found");
            }
            deletedCount += deleted;
        }
        return new BulkDeleteJobsResponse(deletedCount);
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

        if (resolvedMode != TailorMode.AGGRESSIVE
                && tailorEngineRouter != null
                && tailorEngineRouter.useV3()
                && v3BasicTailorService != null
                && resumeAnalysisInputFactory != null) {
            return tailorResumeV3Basic(email, jobId, resumeId, resolvedMode);
        }

        TailorContext context = loadTailorContext(email, jobId, resumeId);

        if (resolvedMode == TailorMode.AGGRESSIVE) {
            List<TailorChange> proposed =
                    aiResumeService.generateTailorChanges(
                            context.nodes(),
                            context.jobDescription(),
                            resolvedMode
                    );
            TailorFilterResult filtered =
                    filterSafeChanges(context.nodes(), proposed, resolvedMode);
            return createPendingProposal(
                    context.user().getId(),
                    context.resume().getId(),
                    context.job().getId(),
                    TailorMode.AGGRESSIVE,
                    filtered.accepted(),
                    filtered.rejected(),
                    List.of(),
                    context.nodes(),
                    context.jobDescription()
            );
        }

        ConservativeRunResult conservative =
                runConservativeTailor(context.nodes(), context.jobDescription());
        List<TailorChange> verified = conservative.accepted().stream()
                .filter(change -> Boolean.TRUE.equals(change.getApplied()))
                .toList();

        Path generatedPath = resumeDocxTailorService.createTailoredDocx(
                context.originalPath(),
                verified,
                context.user().getId(),
                context.job().getId()
        );

        int appliedChangeCount = verified.size();
        PdfExportFields pdf = exportTailoredPdf(context.job().getId(), generatedPath);

        return toTailoredResponse(
                context.resume().getId(),
                context.job().getId(),
                resolvedMode,
                generatedPath.getFileName().toString(),
                generatedPath.toString(),
                pdf.pdfDownloadUrl(),
                pdf.status(),
                conservative.accepted(),
                appliedChangeCount,
                conservative.rejected(),
                null,
                conservative.status(),
                null,
                0,
                false,
                conservative.exploredNodeIds()
        );
    }

    @Override
    public TailoredResumeResponse createTailorProposal(
            String email,
            Long jobId,
            Long resumeId) {
        return createTailorProposal(email, jobId, resumeId, null);
    }

    @Override
    public TailoredResumeResponse createTailorProposal(
            String email,
            Long jobId,
            Long resumeId,
            String productMode) {

        if ("ENHANCED".equalsIgnoreCase(productMode == null ? "" : productMode.strip())) {
            return createEnhancedProposal(email, jobId, resumeId);
        }

        TailorContext context = loadTailorContext(email, jobId, resumeId);
        ConservativeRunResult conservative =
                runConservativeTailor(context.nodes(), context.jobDescription());
        List<TailorChange> accepted = new ArrayList<>(conservative.accepted());
        for (TailorChange change : accepted) {
            change.setApplied(false);
            change.setRequiresConfirmation(true);
        }
        return createPendingProposal(
                context.user().getId(),
                context.resume().getId(),
                context.job().getId(),
                TailorMode.CONSERVATIVE,
                accepted,
                conservative.rejected(),
                conservative.exploredNodeIds(),
                context.nodes(),
                context.jobDescription(),
                conservative
        );
    }

    @Override
    public V3OpportunityMap scanEnhancedOpportunity(
            String email,
            Long jobId,
            Long resumeId,
            List<String> skippedQuestionIds) {
        requireEnhancedServices();
        EnhancedDocxContext context = loadEnhancedContext(email, jobId, resumeId);
        List<V3UserEvidence> evidence = loadEnhancedEvidence(email, resumeId);
        return v3EnhancedOpportunityService.scan(
                context.nodes(), context.jobDescription(), evidence, skippedQuestionIds);
    }

    @Override
    public AnswerEnhancedQuestionResponse answerEnhancedQuestion(
            String email,
            Long jobId,
            Long resumeId,
            AnswerEnhancedQuestionRequest request) {
        requireEnhancedServices();
        if (request == null || isBlank(request.getQuestionId()) || !V3EnhancedAnswerBinder.validStatus(request.getAnswerStatus())) {
            throw new InvalidEvidenceRequestException("questionId and answerStatus are required");
        }
        EnhancedDocxContext context = loadEnhancedContext(email, jobId, resumeId);
        List<V3UserEvidence> evidence = loadEnhancedEvidence(email, resumeId);
        V3EnhancedQuestion question = v3EnhancedOpportunityService.plannedQuestion(
                context.nodes(), context.jobDescription(), evidence, request.getQuestionId());
        if (question == null) {
            throw new InvalidEvidenceRequestException("questionId is not valid for this resume and job");
        }
        String entryKey = question.canonicalEntryKey();
        if (!isBlank(request.getEntryKey())) {
            entryKey = request.getEntryKey().strip();
        }
        if (!question.allowsEntry(entryKey)
                || !v3EnhancedOpportunityService.entryBelongsToResume(context.nodes(), entryKey)) {
            throw new InvalidEvidenceRequestException("entryKey is not valid for this question");
        }
        if (V3EnhancedAnswerBinder.skipped(request.getAnswerStatus())) {
            return new AnswerEnhancedQuestionResponse(
                    question.id(), V3EnhancedAnswerBinder.SKIPPED, entryKey, null, true);
        }
        if (V3EnhancedAnswerBinder.confirmedUsed(request.getAnswerStatus())) {
            if ("maintain".equals(question.topic())
                    && !V3EnhancedAnswerBinder.specificMaintainAnswer(request.getAnswerText())) {
                throw new InvalidEvidenceRequestException(
                        "Describe the actual maintenance work. A yes/no answer is not enough.");
            }
            if (V3EnhancedAnswerBinder.skillGapTopic(question.topic())
                    && !V3EnhancedAnswerBinder.specificSkillGapAnswer(request.getAnswerText())) {
                throw new InvalidEvidenceRequestException(
                        "Describe the actual work. A yes/no answer is not enough.");
            }
            if (isBlank(request.getAnswerText())) {
                throw new InvalidEvidenceRequestException("answerText is required");
            }
        }
        SaveResumeEvidenceRequest save = new SaveResumeEvidenceRequest();
        save.setTopic(V3EnhancedAnswerBinder.canonicalTopicKey(question));
        save.setCategory(V3EnhancedAnswerBinder.evidenceCategory(question));
        save.setAnswerStatus(request.getAnswerStatus().strip());
        save.setEntryKey(entryKey);
        save.setAction(request.getAnswerText());
        save.setConfirmed(true);
        ResumeEvidenceService.SaveResult saved = resumeEvidenceService.save(email, resumeId, save);
        return new AnswerEnhancedQuestionResponse(
                question.id(),
                saved.item().getAnswerStatus(),
                entryKey,
                saved.item(),
                false);
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
        return toProposalResponse(proposal, 0, proposalRejected(proposal), null, null, 0);
    }

    @Override
    public TailoredResumeResponse confirmTailorProposal(
            String email,
            Long jobId,
            Long proposalId,
            List<String> selectedNodeIds) {
        return confirmTailorProposal(email, jobId, proposalId, selectedNodeIds, null);
    }

    @Override
    public TailoredResumeResponse confirmTailorProposal(
            String email,
            Long jobId,
            Long proposalId,
            List<String> selectedNodeIds,
            List<EditedTailorChange> editedChanges) {
        ConfirmTailorProposalRequest request = new ConfirmTailorProposalRequest();
        request.setSelectedNodeIds(selectedNodeIds);
        request.setEditedChanges(editedChanges);
        return confirmTailorProposal(email, jobId, proposalId, request);
    }

    @Override
    public TailoredResumeResponse confirmTailorProposal(
            String email,
            Long jobId,
            Long proposalId,
            ConfirmTailorProposalRequest request) {

        boolean hasEntryKeys = request != null
                && request.getSelectedEntryKeys() != null
                && request.getSelectedEntryKeys().stream().anyMatch(key -> !isBlank(key));
        boolean hasSkillReviews = request != null
                && request.getSelectedSkillReviewIds() != null
                && request.getSelectedSkillReviewIds().stream().anyMatch(id -> !isBlank(id));
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        List<EditedTailorChange> edits;
        TailorProposalState peekState = null;
        boolean enhanced = false;
        User user;
        if (!hasEntryKeys && !hasSkillReviews) {
            if (request != null && request.getSelectedNodeIds() != null) {
                for (String nodeId : request.getSelectedNodeIds()) {
                    if (isBlank(nodeId)) {
                        continue;
                    }
                    if (!selected.add(nodeId)) {
                        throw new InvalidTailorProposalException("duplicate nodeId");
                    }
                }
            }
            if (selected.isEmpty()) {
                throw new InvalidTailorProposalException("selectedNodeIds must not be empty");
            }
            edits = normalizeEditedChanges(request == null ? null : request.getEditedChanges(), selected);
            user = getUserByEmail(email);
            getOwnedJob(jobId, user.getId());
        } else {
            user = getUserByEmail(email);
            getOwnedJob(jobId, user.getId());
            TailorProposal peek = getVisibleProposal(proposalId, user.getId(), jobId);
            peekState = readProposalState(peek.getChangesJson());
            enhanced = isEnhanced(peekState);
            if (hasEntryKeys) {
                for (String key : request.getSelectedEntryKeys()) {
                    if (isBlank(key)) {
                        continue;
                    }
                    selected.addAll(nodeIdsForEntry(peekState, key));
                }
            }
            if (hasSkillReviews) {
                for (String skillReviewId : request.getSelectedSkillReviewIds()) {
                    if (isBlank(skillReviewId)) {
                        continue;
                    }
                    selected.addAll(nodeIdsForSkillReview(peekState, skillReviewId));
                }
            }
            if (selected.isEmpty()) {
                throw new InvalidTailorProposalException("selectedNodeIds must not be empty");
            }
            List<EditedTailorChange> merged = new ArrayList<>(
                    mergeEditedChanges(request, peekState, selected, enhanced));
            merged.addAll(mergeEditedSkillReviews(request, peekState, selected, enhanced));
            edits = normalizeEditedChanges(merged, selected);
        }

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
            Path originalPath;
            List<ResumeDocumentNode> nodes;
            TailorProposalState storedState = readProposalState(proposal.getChangesJson());
            boolean enhancedProposal = isEnhanced(storedState);
            if (enhancedProposal) {
                EnhancedDocxContext docx = loadEnhancedDocx(user.getId(), resume);
                originalPath = docx.path();
                nodes = docx.nodes();
            } else {
                if (!"docx".equalsIgnoreCase(resume.getFileType())) {
                    throw new InvalidResumeFileException(
                            "Resume tailoring currently supports DOCX only"
                    );
                }
                originalPath = Path.of(resume.getFilePath());
                if (!Files.exists(originalPath)) {
                    throw new InvalidResumeFileException("Resume file not found");
                }
                nodes = resumeDocumentService.parseDocx(originalPath);
            }
            List<TailorChange> stored = readProposalState(proposal.getChangesJson()).getChanges();
            applyUserEdits(stored, selected, edits, nodes);
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

            PdfExportFields pdf = exportTailoredPdf(jobId, generatedPath);

            TailoredResumeResponse response = toTailoredResponse(
                    resume.getId(),
                    jobId,
                    proposalMode(proposal),
                    generatedPath.getFileName().toString(),
                    generatedPath.toString(),
                    pdf.pdfDownloadUrl(),
                    pdf.status(),
                    responseChanges,
                    selectedResult.accepted().size(),
                    selectedResult.rejected(),
                    proposal.getId(),
                    TailorProposal.STATUS_CONFIRMED,
                    proposal.getExpiresAt(),
                    0,
                    false,
                    List.of()
            );
            applyEnhancedEnvelope(response, storedState);
            return response;
        } finally {
            if (!completed) {
                tailorProposalMapper.restorePending(proposalId);
            }
        }
    }

    @Override
    public TailoredResumeResponse continueTailorProposal(
            String email,
            Long jobId,
            Long proposalId) {

        User user = getUserByEmail(email);
        Job job = getOwnedJob(jobId, user.getId());

        int claimed = tailorProposalMapper.claimPending(proposalId, user.getId(), jobId);
        if (claimed == 0) {
            throwContinueUnavailable(proposalId, user.getId(), jobId);
        }

        boolean restored = false;
        try {
            TailorProposal proposal = tailorProposalMapper.findById(proposalId);
            if (proposal == null) {
                throw new TailorProposalNotFoundException("Tailor proposal not found");
            }
            if (isEnhanced(readProposalState(proposal.getChangesJson()))) {
                throw new InvalidTailorProposalException("ENHANCED proposals do not support continue");
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

            List<ResumeDocumentNode> nodes = resumeDocumentService.parseDocx(originalPath);
            TailorProposalState state = readProposalState(proposal.getChangesJson());
            List<RejectedTailorChange> storedRejected = new ArrayList<>(proposalRejected(proposal));
            ConservativeRunResult next = runConservativeContinue(
                    nodes,
                    job.getDescription(),
                    state.getChanges(),
                    state.getExploredNodeIds()
            );

            List<TailorChange> merged = new ArrayList<>(state.getChanges());
            Set<String> existingIds = new LinkedHashSet<>();
            for (TailorChange change : merged) {
                if (change != null && change.getNodeId() != null) {
                    existingIds.add(change.getNodeId());
                }
            }
            int added = 0;
            for (TailorChange change : next.accepted()) {
                if (change == null || change.getNodeId() == null || existingIds.contains(change.getNodeId())) {
                    continue;
                }
                change.setApplied(false);
                change.setRequiresConfirmation(true);
                merged.add(change);
                existingIds.add(change.getNodeId());
                added++;
            }
            storedRejected.addAll(next.rejected());

            LinkedHashSet<String> explored = new LinkedHashSet<>();
            if (state.getExploredNodeIds() != null) {
                explored.addAll(state.getExploredNodeIds());
            }
            if (next.exploredNodeIds() != null) {
                explored.addAll(next.exploredNodeIds());
            }
            List<String> previousNoChange = state.getNoChangeNodeIds() == null
                    ? List.of()
                    : new ArrayList<>(state.getNoChangeNodeIds());
            TailorProposalState updated = TailorProposalState.fromChanges(merged, new ArrayList<>(explored));
            updated.setNoChangeNodeIds(previousNoChange);
            applyCoverage(updated, next, nodes, job.getDescription(), merged);

            int written = tailorProposalMapper.updateConfirmingContents(
                    proposalId,
                    writeJson(updated),
                    writeJson(storedRejected)
            );
            if (written == 0) {
                throw new TailorProposalConflictException("Tailor proposal is already being confirmed");
            }
            int restoredRows = tailorProposalMapper.restorePending(proposalId);
            if (restoredRows == 0) {
                throw new TailorProposalConflictException("Tailor proposal is already being confirmed");
            }
            restored = true;
            proposal.setChangesJson(writeJson(updated));
            proposal.setRejectedJson(writeJson(storedRejected));
            proposal.setStatus(TailorProposal.STATUS_PENDING);
            return toProposalResponse(proposal, 0, storedRejected, null, null, added);
        } finally {
            if (!restored) {
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

        Path jobDirectory = storagePathResolver
                .tailoredJobDirectory(user.getId(), jobId)
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

    @Override
    public JobSearchResponse searchJobs(String email, JobSearchRequest request) {
        User user = getUserByEmail(email);
        if (request == null || request.keywords() == null || request.keywords().isBlank()) {
            throw new InvalidJobSearchException("Search keywords are required");
        }
        if (jobSearchProviderRegistry == null || providerQueryCache == null || searchSessionStore == null) {
            throw new JobSearchUnavailableException("Job search is temporarily unavailable.");
        }
        if (request.postedWithinDays() != null
                && !JobSearchRequest.ALLOWED_POSTED_WITHIN_DAYS.contains(request.postedWithinDays())) {
            throw new InvalidJobSearchException("postedWithinDays must be 1, 3, 7, 14, or 30");
        }
        JobSearchProvider provider = jobSearchProviderRegistry.primary();
        String cacheKey = JobSearchCacheKeys.queryKey(
                provider.providerName(),
                JSearchClientConfig.COUNTRY,
                request);
        ProviderSearchPage page = providerQueryCache.getOrLoad(cacheKey, () -> provider.search(request));
        if (page == null) {
            page = new ProviderSearchPage(List.of(), false, null);
        }
        SearchSession session = searchSessionStore.create(
                user.getId(),
                provider.providerName(),
                provider.capability(),
                request,
                page.results(),
                page.nextCursor());
        List<Job> existing = jobMapper.findByUserId(user.getId());
        List<JobSearchResult> previews = new ArrayList<>();
        for (ProviderJobHit hit : page.results()) {
            previews.add(toSearchPreview(hit, existing, provider.capability()));
        }
        int pageSize = JSearchJobSearchProvider.SOURCE.equals(provider.providerName())
                ? JSearchJobSearchProvider.PAGE_SIZE
                : request.pageSize();
        int maxPages = jobSearchProperties.maxPagesPerSearch();
        boolean hasMore = session.nextCursor() != null && session.currentPage() < maxPages;
        boolean limitReached = session.nextCursor() != null && session.currentPage() >= maxPages;
        return new JobSearchResponse(
                session.id(),
                provider.providerName(),
                request.page(),
                pageSize,
                hasMore,
                page.totalCount(),
                previews,
                limitReached ? true : null,
                session.hits().size());
    }

    @Override
    public JobSearchResponse loadNextSearchPage(String email, UUID searchSessionId, int expectedPage) {
        User user = getUserByEmail(email);
        if (jobSearchProviderRegistry == null || providerQueryCache == null || searchSessionStore == null) {
            throw new JobSearchUnavailableException("Job search is temporarily unavailable.");
        }
        if (expectedPage < 1) {
            throw new InvalidJobSearchException("expectedPage must be at least 1");
        }
        Object lock = searchSessionLocks.computeIfAbsent(searchSessionId, id -> new Object());
        synchronized (lock) {
            SearchSession session = searchSessionStore.requireForUser(searchSessionId, user.getId());
            int maxPages = jobSearchProperties.maxPagesPerSearch();
            int pageSize = JSearchJobSearchProvider.SOURCE.equals(session.provider())
                    ? JSearchJobSearchProvider.PAGE_SIZE
                    : session.request().pageSize();
            List<Job> existing = jobMapper.findByUserId(user.getId());
            if (session.hasLoadedPage(expectedPage + 1)) {
                return toLoadedPageResponse(session, expectedPage + 1, pageSize, existing, maxPages);
            }
            if (session.currentPage() != expectedPage) {
                throw new JobSearchPageConflictException("Search page state conflict");
            }
            if (session.currentPage() >= maxPages) {
                return new JobSearchResponse(
                        session.id(),
                        session.provider(),
                        session.currentPage(),
                        pageSize,
                        false,
                        null,
                        List.of(),
                        true,
                        session.hits().size());
            }
            if (session.nextCursor() == null) {
                return new JobSearchResponse(
                        session.id(),
                        session.provider(),
                        session.currentPage(),
                        pageSize,
                        false,
                        null,
                        List.of(),
                        false,
                        session.hits().size());
            }
            JobSearchProvider provider = jobSearchProviderRegistry.require(session.provider());
            String cursor = session.nextCursor();
            String cursorHash = JobSearchCacheKeys.cursorHash(cursor);
            String cacheKey = JobSearchCacheKeys.nextPageKey(
                    provider.providerName(),
                    JSearchClientConfig.COUNTRY,
                    session.request(),
                    cursorHash);
            ProviderSearchPage page = providerQueryCache.getOrLoad(
                    cacheKey,
                    () -> provider.search(session.request(), cursor));
            if (page == null) {
                page = new ProviderSearchPage(List.of(), false, null);
            }
            Set<String> rawIds = new HashSet<>(session.seenRawIds());
            Set<String> urls = new HashSet<>(session.seenCanonicalUrls());
            Set<String> fingerprints = new HashSet<>(session.seenFingerprints());
            JobSearchDeduper.UniquePage unique = JobSearchDeduper.uniquePage(
                    page.results(), rawIds, urls, fingerprints);
            SearchSession updated = searchSessionStore.replace(
                    session.withNextPage(unique.hits(), page.nextCursor(), cursorHash));
            log.info("reasonCode=SEARCH_NEXT_OK provider={} queryHash={} cursorHash={} uniqueCount={}",
                    provider.providerName(),
                    JobSearchCacheKeys.queryKey(
                            provider.providerName(),
                            JSearchClientConfig.COUNTRY,
                            session.request()),
                    cursorHash,
                    unique.hits().size());
            return toLoadedPageResponse(updated, updated.currentPage(), pageSize, existing, maxPages);
        }
    }

    private JobSearchResponse toLoadedPageResponse(
            SearchSession session,
            int page,
            int pageSize,
            List<Job> existing,
            int maxPages) {
        List<JobSearchResult> previews = new ArrayList<>();
        for (ProviderJobHit hit : session.pageHits(page)) {
            previews.add(toSearchPreview(hit, existing, session.capability()));
        }
        boolean limitReached = session.currentPage() >= maxPages;
        boolean hasMore = session.nextCursor() != null && !limitReached;
        return new JobSearchResponse(
                session.id(),
                session.provider(),
                page,
                pageSize,
                hasMore,
                null,
                previews,
                limitReached,
                session.hits().size());
    }

    @Override
    public ImportSearchResultsResponse importSearchResults(String email, ImportSearchResultsRequest request) {
        User user = getUserByEmail(email);
        if (request == null || request.getSearchSessionId() == null) {
            throw new InvalidJobSearchException("searchSessionId is required");
        }
        List<String> selected = request.getSelectedExternalJobIds() == null
                ? List.of()
                : request.getSelectedExternalJobIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::strip)
                .toList();
        if (selected.isEmpty()) {
            throw new InvalidJobSearchException("Select at least one job to import");
        }
        if (selected.size() > MAX_SEARCH_IMPORTS) {
            throw new InvalidJobSearchException("At most 20 jobs can be imported at once");
        }
        SearchSession session = searchSessionStore.requireForUser(request.getSearchSessionId(), user.getId());
        Map<String, ProviderJobHit> hits = session.hitsByRawId();
        ImportSearchResultsResponse response = new ImportSearchResultsResponse();
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>(selected);
        for (String rawId : uniqueIds) {
            ProviderJobHit hit = hits.get(rawId);
            if (hit == null) {
                response.getFailed().add(new ImportSearchResultsResponse.FailedSearchImport(
                        rawId, "UNKNOWN_JOB"));
                continue;
            }
            try {
                importOneHit(user, session, hit, response);
            } catch (RuntimeException ex) {
                if (isDuplicateKey(ex)) {
                    Job existing = findPersistedHit(user.getId(), hit);
                    if (existing != null) {
                        response.getExisting().add(new ImportSearchResultsResponse.ImportedSearchJob(
                                rawId, existing.getId()));
                        continue;
                    }
                }
                log.warn("reasonCode=IMPORT_FAILED provider={} exceptionClass={}",
                        session.provider(), ex.getClass().getName());
                response.getFailed().add(new ImportSearchResultsResponse.FailedSearchImport(
                        rawId, "DESCRIPTION_MISSING"));
            }
        }
        return response;
    }

    private JobSearchResult toSearchPreview(
            ProviderJobHit hit,
            List<Job> existing,
            JobSearchCapability capability) {
        JobSearchResult preview = new JobSearchResult();
        preview.setExternalJobId(hit.rawExternalJobId());
        preview.setTitle(hit.title());
        preview.setCompany(hit.company());
        preview.setLocation(hit.location());
        preview.setPostedAt(hit.postedAt());
        preview.setDescriptionSnippet(snippet(hit.fullDescription()));
        preview.setSource(hit.source());
        preview.setSourceUrl(hit.sourceUrl());
        preview.setPublisher(hit.publisher());
        preview.setFullDescriptionAvailable(fullDescriptionAvailable(capability, hit));
        String persistId = persistExternalId(hit.source(), hit.rawExternalJobId());
        preview.setAlreadySaved(findExistingSearchJob(
                existing,
                hit.source(),
                persistId,
                hit.company(),
                hit.title(),
                hit.location(),
                hit.sourceUrl()) != null);
        return preview;
    }

    private void importOneHit(
            User user,
            SearchSession session,
            ProviderJobHit hit,
            ImportSearchResultsResponse response) {
        Job duplicate = findPersistedHit(user.getId(), hit);
        if (duplicate != null) {
            response.getExisting().add(new ImportSearchResultsResponse.ImportedSearchJob(
                    hit.rawExternalJobId(), duplicate.getId()));
            return;
        }
        String description = resolveFullDescription(session.capability(), hit);
        if (description == null || description.isBlank()) {
            response.getFailed().add(new ImportSearchResultsResponse.FailedSearchImport(
                    hit.rawExternalJobId(), "DESCRIPTION_MISSING"));
            return;
        }
        Job job = new Job();
        job.setUserId(user.getId());
        job.setCompany(hit.company());
        job.setTitle(hit.title());
        job.setLocation(trimToNull(hit.location()));
        job.setJobUrl(hit.sourceUrl());
        job.setDescription(description);
        job.setSource(session.provider());
        job.setStatus(STATUS_SAVED);
        job.setExternalJobId(persistExternalId(session.provider(), hit.rawExternalJobId()));
        jobMapper.insert(job);
        Job saved = jobMapper.findById(job.getId());
        response.getImported().add(new ImportSearchResultsResponse.ImportedSearchJob(
                hit.rawExternalJobId(), (saved != null ? saved : job).getId()));
    }

    private String resolveFullDescription(JobSearchCapability capability, ProviderJobHit hit) {
        if (capability == JobSearchCapability.FULL_DESCRIPTION) {
            String text = hit.fullDescription() == null ? "" : hit.fullDescription().strip();
            if (!text.isBlank()) {
                return boundDescription(text);
            }
        }
        return fetchGreenhouseDescription(hit.sourceUrl());
    }

    private String fetchGreenhouseDescription(String sourceUrl) {
        var official = GreenhouseJobUrl.parseOfficialJobUrl(sourceUrl);
        if (official.isEmpty()) {
            return null;
        }
        try {
            var posting = greenhouseJobClient.fetchJob(official.get().boardToken(), official.get().jobId());
            if (posting == null) {
                return null;
            }
            String text = HtmlTextExtractor.toPlainText(posting.htmlContent());
            return text.isBlank() ? null : boundDescription(text);
        } catch (RuntimeException ex) {
            log.warn("reasonCode=GREENHOUSE_FALLBACK_FAILED exceptionClass={}", ex.getClass().getName());
            return null;
        }
    }

    private boolean fullDescriptionAvailable(JobSearchCapability capability, ProviderJobHit hit) {
        if (capability != JobSearchCapability.FULL_DESCRIPTION) {
            return GreenhouseJobUrl.parseOfficialJobUrl(hit.sourceUrl()).isPresent();
        }
        return (hit.fullDescription() != null && !hit.fullDescription().isBlank())
                || GreenhouseJobUrl.parseOfficialJobUrl(hit.sourceUrl()).isPresent();
    }

    private Job findPersistedHit(Long userId, ProviderJobHit hit) {
        String persistId = persistExternalId(hit.source(), hit.rawExternalJobId());
        if (persistId != null) {
            Job byId = jobMapper.findByUserIdAndSourceAndExternalJobId(userId, hit.source(), persistId);
            if (byId != null) {
                return byId;
            }
        }
        List<Job> existing = jobMapper.findByUserId(userId);
        return findExistingSearchJob(
                existing,
                hit.source(),
                persistId,
                hit.company(),
                hit.title(),
                hit.location(),
                hit.sourceUrl());
    }

    private static String persistExternalId(String source, String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        if (SOURCE_JSEARCH.equals(source)) {
            return ExternalJobIds.persistId(source, rawId);
        }
        return rawId;
    }

    private static String snippet(String fullDescription) {
        if (fullDescription == null || fullDescription.isBlank()) {
            return "";
        }
        String text = fullDescription.strip();
        if (text.length() <= DESCRIPTION_SNIPPET_CHARS) {
            return text;
        }
        return text.substring(0, DESCRIPTION_SNIPPET_CHARS).strip();
    }

    private static String boundDescription(String text) {
        if (text.length() <= JSearchJobSearchProvider.MAX_DESCRIPTION_CHARS) {
            return text;
        }
        return text.substring(0, JSearchJobSearchProvider.MAX_DESCRIPTION_CHARS);
    }

    private static boolean isDuplicateKey(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof DuplicateKeyException || current instanceof DataIntegrityViolationException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("duplicate")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public SaveJobSearchResultResponse saveSearchResult(String email, SaveJobSearchResultRequest request) {
        User user = getUserByEmail(email);
        if (request == null) {
            throw new InvalidJobSearchException("Full job description is required");
        }
        String fullDescription = request.getFullDescription() == null ? "" : request.getFullDescription().strip();
        if (fullDescription.isBlank()) {
            throw new InvalidJobSearchException("Full job description is required");
        }
        String snippet = request.getDescriptionSnippet() == null ? "" : request.getDescriptionSnippet().strip();
        if (!snippet.isBlank() && JobFingerprint.normalize(fullDescription).equals(JobFingerprint.normalize(snippet))) {
            throw new InvalidJobSearchException(
                    "Search snippets cannot be saved as the job description");
        }
        String source = request.getSource() == null || request.getSource().isBlank()
                ? SOURCE_ADZUNA
                : request.getSource().strip();
        if (!SOURCE_ADZUNA.equals(source)) {
            throw new InvalidJobSearchException("Only Adzuna search results can be saved from search");
        }
        String title = request.getTitle() == null ? "" : request.getTitle().strip();
        String company = request.getCompany() == null ? "" : request.getCompany().strip();
        String sourceUrl = request.getSourceUrl() == null ? "" : request.getSourceUrl().strip();
        if (title.isBlank() || company.isBlank() || sourceUrl.isBlank()) {
            throw new InvalidJobSearchException("Title, company, and original job URL are required");
        }
        List<Job> existing = jobMapper.findByUserId(user.getId());
        Job duplicate = findExistingSearchJob(
                existing,
                source,
                request.getExternalJobId(),
                company,
                title,
                request.getLocation(),
                sourceUrl);
        if (duplicate != null) {
            return new SaveJobSearchResultResponse(false, toJobResponse(duplicate));
        }

        Job job = new Job();
        job.setUserId(user.getId());
        job.setCompany(company);
        job.setTitle(title);
        job.setLocation(trimToNull(request.getLocation()));
        job.setJobUrl(sourceUrl);
        job.setDescription(fullDescription);
        job.setSource(SOURCE_ADZUNA);
        job.setStatus(STATUS_SAVED);
        job.setExternalJobId(trimToNull(request.getExternalJobId()));
        jobMapper.insert(job);
        Job saved = jobMapper.findById(job.getId());
        return new SaveJobSearchResultResponse(true, toJobResponse(saved != null ? saved : job));
    }

    private Job findExistingSearchJob(
            List<Job> existing,
            String source,
            String externalJobId,
            String company,
            String title,
            String location,
            String sourceUrl) {

        if (existing == null) {
            return null;
        }
        String external = trimToNull(externalJobId);
        String fingerprint = JobFingerprint.of(company, title, location, sourceUrl);
        String normalizedUrl = JobFingerprint.normalizeUrl(sourceUrl);
        for (Job job : existing) {
            if (job == null) {
                continue;
            }
            if (external != null
                    && source != null
                    && source.equals(job.getSource())
                    && external.equals(job.getExternalJobId())) {
                return job;
            }
            if (normalizedUrl.equals(JobFingerprint.normalizeUrl(job.getJobUrl()))) {
                return job;
            }
            if (fingerprint.equals(JobFingerprint.of(
                    job.getCompany(), job.getTitle(), job.getLocation(), job.getJobUrl()))) {
                return job;
            }
        }
        return null;
    }

    private TailoredResumeResponse tailorResumeV3Basic(
            String email,
            Long jobId,
            Long resumeId,
            TailorMode mode) {

        User user = getUserByEmail(email);
        Job job = getOwnedJob(jobId, user.getId());
        Resume selected = getOwnedResume(resumeId, user.getId());
        String jobDescription = job.getDescription();
        if (jobDescription == null || jobDescription.isBlank()) {
            throw new ResumeTextNotAvailableException(
                    "Job description is not available for matching"
            );
        }
        ResumeAnalysisInput input = resumeAnalysisInputFactory.resolve(selected, user.getId());
        Path docxPath = v3DocxPath(input);
        List<ResumeDocumentNode> nodes = resumeDocumentService.parseDocx(docxPath);
        V3BasicRunResult result = v3BasicTailorService.run(nodes, jobDescription);
        Path generatedPath = resumeDocxTailorService.createTailoredDocx(
                docxPath,
                result.changes(),
                user.getId(),
                job.getId()
        );
        PdfExportFields pdf = exportTailoredPdf(job.getId(), generatedPath);
        TailoredResumeResponse response = toTailoredResponse(
                selected.getId(),
                job.getId(),
                mode,
                generatedPath.getFileName().toString(),
                generatedPath.toString(),
                pdf.pdfDownloadUrl(),
                pdf.status(),
                result.changes(),
                result.changes().size(),
                result.rejected(),
                null,
                TailoredResumeResponse.STATUS_APPLIED,
                null,
                0,
                false,
                List.of()
        );
        response.setEngineVersion("V3");
        response.setProductMode("BASIC");
        response.setRewrittenEntryCount(result.rewrittenEntryCount());
        response.setUnchangedEntryCount(result.unchangedEntryCount());
        return response;
    }

    private static Path v3DocxPath(ResumeAnalysisInput input) {
        Resume child = input.canonicalChildResume();
        if (isExistingDocx(child)) {
            return Path.of(child.getFilePath());
        }
        Resume selected = input.selectedResume();
        if (isExistingDocx(selected)) {
            return Path.of(selected.getFilePath());
        }
        throw new InvalidResumeFileException(
                "V3 BASIC requires a canonical or native DOCX"
        );
    }

    private static boolean isExistingDocx(Resume resume) {
        if (resume == null || resume.getFilePath() == null || resume.getFilePath().isBlank()) {
            return false;
        }
        if (resume.getFileType() == null || !"docx".equalsIgnoreCase(resume.getFileType())) {
            return false;
        }
        return Files.exists(Path.of(resume.getFilePath()));
    }

    private void requireEnhancedServices() {
        if (v3EnhancedOpportunityService == null
                || v3EnhancedTailorService == null
                || resumeEvidenceService == null
                || resumeAnalysisInputFactory == null) {
            throw new InvalidTailorProposalException("ENHANCED mode is not available");
        }
    }

    private TailoredResumeResponse createEnhancedProposal(String email, Long jobId, Long resumeId) {
        requireEnhancedServices();
        EnhancedDocxContext context = loadEnhancedContext(email, jobId, resumeId);
        List<V3UserEvidence> evidence = loadEnhancedEvidence(email, resumeId);
        V3OpportunityMap map = v3EnhancedOpportunityService.scan(
                context.nodes(), context.jobDescription(), evidence, List.of());
        V3EnhancedRunResult result = v3EnhancedTailorService.run(
                context.nodes(), context.jobDescription(), evidence, map);
        List<TailorChange> accepted = new ArrayList<>();
        for (TailorChange change : result.changes()) {
            if (change == null) {
                continue;
            }
            change.setApplied(false);
            change.setRequiresConfirmation(true);
            accepted.add(change);
        }
        TailorProposalState state = TailorProposalState.fromChanges(accepted, List.of());
        state.setHasMoreSuggestions(Boolean.FALSE);
        state.setCoverageStatus(TailorProposalState.COVERAGE_COMPLETE);
        state.setEngineVersion("V3");
        state.setProductMode("ENHANCED");
        state.setEntryReviews(new ArrayList<>(result.entryReviews()));
        state.setEvidenceItemIds(new ArrayList<>(result.evidenceItemIds()));
        state.setEvidenceUsage(new ArrayList<>(result.evidenceUsage()));
        state.setSkillReviews(new ArrayList<>(result.skillReviews()));

        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(PROPOSAL_TTL_HOURS);
        TailorProposal proposal = new TailorProposal();
        proposal.setUserId(context.user().getId());
        proposal.setResumeId(context.resumeId());
        proposal.setJobId(jobId);
        proposal.setMode(TailorMode.CONSERVATIVE.name());
        proposal.setStatus(TailorProposal.STATUS_PENDING);
        proposal.setExpiresAt(expiresAt);
        proposal.setChangesJson(writeJson(state));
        proposal.setRejectedJson(writeJson(List.of()));
        tailorProposalMapper.insert(proposal);

        TailoredResumeResponse response = toTailoredResponse(
                context.resumeId(),
                jobId,
                TailorMode.CONSERVATIVE,
                null,
                null,
                null,
                null,
                accepted,
                0,
                List.of(),
                proposal.getId(),
                TailorProposal.STATUS_PENDING,
                expiresAt,
                0,
                false,
                state.getExploredNodeIds(),
                state.getEligibleNodeIds(),
                state.getRemainingNodeIds(),
                state.getNoChangeNodeIds(),
                state.getCoverageStatus()
        );
        applyEnhancedEnvelope(response, state);
        response.setRewrittenEntryCount(result.rewrittenEntryCount());
        response.setUnchangedEntryCount(result.unchangedEntryCount());
        return response;
    }

    private EnhancedDocxContext loadEnhancedContext(String email, Long jobId, Long resumeId) {
        User user = getUserByEmail(email);
        Job job = getOwnedJob(jobId, user.getId());
        Resume resume = getOwnedResume(resumeId, user.getId());
        String jobDescription = job.getDescription();
        if (jobDescription == null || jobDescription.isBlank()) {
            throw new ResumeTextNotAvailableException(
                    "Job description is not available for matching");
        }
        EnhancedDocxContext docx = loadEnhancedDocx(user.getId(), resume);
        return new EnhancedDocxContext(
                user, resume.getId(), jobDescription, docx.path(), docx.nodes());
    }

    private EnhancedDocxContext loadEnhancedDocx(Long userId, Resume resume) {
        ResumeAnalysisInput input = resumeAnalysisInputFactory.resolve(resume, userId);
        Path path = enhancedDocxPath(input);
        return new EnhancedDocxContext(null, resume.getId(), "", path, resumeDocumentService.parseDocx(path));
    }

    private static Path enhancedDocxPath(ResumeAnalysisInput input) {
        Resume child = input.canonicalChildResume();
        if (isExistingDocx(child)) {
            return Path.of(child.getFilePath());
        }
        Resume selected = input.selectedResume();
        if (isExistingDocx(selected)) {
            return Path.of(selected.getFilePath());
        }
        throw new InvalidResumeFileException("ENHANCED requires a canonical or native DOCX");
    }

    private List<V3UserEvidence> loadEnhancedEvidence(String email, Long resumeId) {
        List<V3UserEvidence> evidence = new ArrayList<>();
        for (ResumeEvidenceItemResponse item : resumeEvidenceService.list(email, resumeId)) {
            if (item == null) {
                continue;
            }
            evidence.add(new V3UserEvidence(
                    item.getId(),
                    item.getEntryKey(),
                    item.getAnswerStatus(),
                    item.getAction() == null ? "" : item.getAction(),
                    item.getResult() == null ? "" : item.getResult(),
                    item.getMetric() == null ? "" : item.getMetric(),
                    item.getContext() == null ? "" : item.getContext(),
                    item.getTopic() == null ? "" : item.getTopic(),
                    item.getTopicKey() == null ? "" : item.getTopicKey()));
        }
        return evidence;
    }

    private boolean isEnhanced(TailorProposalState state) {
        return state != null && "ENHANCED".equalsIgnoreCase(state.getProductMode());
    }

    private void applyEnhancedEnvelope(TailoredResumeResponse response, TailorProposalState state) {
        if (response == null || state == null) {
            return;
        }
        if (!isBlank(state.getEngineVersion())) {
            response.setEngineVersion(state.getEngineVersion());
        }
        if (!isBlank(state.getProductMode())) {
            response.setProductMode(state.getProductMode());
        }
        response.setEntryReviews(state.getEntryReviews());
        response.setEvidenceItemIds(state.getEvidenceItemIds());
        response.setEvidenceUsage(state.getEvidenceUsage());
        response.setSkillReviews(state.getSkillReviews());
    }

    private List<String> nodeIdsForEntry(TailorProposalState state, String canonicalEntryKey) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (state == null || isBlank(canonicalEntryKey) || state.getEntryReviews() == null) {
            return List.of();
        }
        for (V3EntryReview review : state.getEntryReviews()) {
            if (review == null || !canonicalEntryKey.equals(review.getCanonicalEntryKey())) {
                continue;
            }
            addReviewNodeIds(ids, review.getOriginalNodes());
            addReviewNodeIds(ids, review.getProposedNodes());
        }
        return new ArrayList<>(ids);
    }

    private void addReviewNodeIds(Set<String> ids, List<V3ReviewNode> nodes) {
        if (nodes == null) {
            return;
        }
        for (V3ReviewNode node : nodes) {
            if (node != null && !isBlank(node.getNodeId())) {
                ids.add(node.getNodeId());
            }
        }
    }

    private List<String> nodeIdsForSkillReview(TailorProposalState state, String skillReviewId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (state == null || isBlank(skillReviewId) || state.getSkillReviews() == null) {
            return List.of();
        }
        for (V3SkillReview review : state.getSkillReviews()) {
            if (review == null || !skillReviewId.equals(review.getSkillReviewId())) {
                continue;
            }
            addReviewNodeIds(ids, review.getOriginalNodes());
            addReviewNodeIds(ids, review.getProposedNodes());
        }
        return new ArrayList<>(ids);
    }

    private List<EditedTailorChange> mergeEditedSkillReviews(
            ConfirmTailorProposalRequest request,
            TailorProposalState state,
            Set<String> selected,
            boolean enhanced) {
        List<EditedTailorChange> merged = new ArrayList<>();
        if (!enhanced || request == null || request.getEditedSkillReviews() == null) {
            return merged;
        }
        for (EditedSkillReview edit : request.getEditedSkillReviews()) {
            if (edit == null || isBlank(edit.getSkillReviewId())) {
                throw new InvalidTailorProposalException("editedSkillReviews skillReviewId is required");
            }
            List<String> skillNodes = nodeIdsForSkillReview(state, edit.getSkillReviewId());
            if (skillNodes.isEmpty()) {
                throw new InvalidTailorProposalException("skill review is not part of this proposal");
            }
            boolean selectedReview = skillNodes.stream().anyMatch(selected::contains);
            if (!selectedReview) {
                continue;
            }
            List<EditedTailorChange> nodeEdits = edit.getNodes();
            if ((nodeEdits == null || nodeEdits.isEmpty()) && !isBlank(edit.getProposedSkills())) {
                nodeEdits = skillEditsFromText(state, edit.getSkillReviewId(), edit.getProposedSkills());
            }
            if (nodeEdits == null) {
                continue;
            }
            Set<String> allowed = new LinkedHashSet<>(skillNodes);
            for (EditedTailorChange node : nodeEdits) {
                if (node == null || isBlank(node.getNodeId())) {
                    throw new InvalidTailorProposalException("edited nodeId is required");
                }
                if (!allowed.contains(node.getNodeId())) {
                    throw new InvalidTailorProposalException("node cannot be edited");
                }
                if (!selected.contains(node.getNodeId())) {
                    throw new InvalidTailorProposalException("edited nodeId must be selected");
                }
                merged.add(node);
            }
        }
        return merged;
    }

    private List<EditedTailorChange> skillEditsFromText(
            TailorProposalState state,
            String skillReviewId,
            String proposedSkills) {
        V3SkillReview review = null;
        if (state.getSkillReviews() != null) {
            for (V3SkillReview item : state.getSkillReviews()) {
                if (item != null && skillReviewId.equals(item.getSkillReviewId())) {
                    review = item;
                    break;
                }
            }
        }
        if (review == null || review.getProposedNodes() == null || review.getProposedNodes().isEmpty()) {
            return List.of();
        }
        List<V3ReviewNode> nodes = review.getProposedNodes();
        String[] lines = proposedSkills.split("\\R", -1);
        List<EditedTailorChange> edits = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            V3ReviewNode node = nodes.get(i);
            if (node == null || isBlank(node.getNodeId())) {
                continue;
            }
            String text;
            if (i == nodes.size() - 1) {
                text = String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length));
            } else {
                text = i < lines.length ? lines[i] : "";
            }
            edits.add(new EditedTailorChange(node.getNodeId(), text));
        }
        return edits;
    }

    private List<EditedTailorChange> mergeEditedChanges(
            ConfirmTailorProposalRequest request,
            TailorProposalState state,
            Set<String> selected,
            boolean enhanced) {
        List<EditedTailorChange> merged = new ArrayList<>();
        if (request != null && request.getEditedChanges() != null) {
            merged.addAll(request.getEditedChanges());
        }
        if (!enhanced || request == null || request.getEditedEntries() == null) {
            return merged;
        }
        for (EditedTailorEntry entry : request.getEditedEntries()) {
            if (entry == null || isBlank(entry.getCanonicalEntryKey())) {
                throw new InvalidTailorProposalException("editedEntries canonicalEntryKey is required");
            }
            Set<String> entryNodes = new LinkedHashSet<>(nodeIdsForEntry(state, entry.getCanonicalEntryKey()));
            if (entry.getNodes() == null) {
                continue;
            }
            for (EditedTailorChange node : entry.getNodes()) {
                if (node == null || isBlank(node.getNodeId())) {
                    throw new InvalidTailorProposalException("edited nodeId is required");
                }
                if (!entryNodes.contains(node.getNodeId())) {
                    throw new InvalidTailorProposalException("node cannot be edited");
                }
                if (!selected.contains(node.getNodeId())) {
                    throw new InvalidTailorProposalException("edited nodeId must be selected");
                }
                merged.add(node);
            }
        }
        return merged;
    }

    private record EnhancedDocxContext(
            User user,
            Long resumeId,
            String jobDescription,
            Path path,
            List<ResumeDocumentNode> nodes
    ) {
    }

    private TailorContext loadTailorContext(String email, Long jobId, Long resumeId) {
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

        return new TailorContext(
                user,
                job,
                resume,
                originalPath,
                resumeDocumentService.parseDocx(originalPath),
                jobDescription
        );
    }

    private TailorMode proposalMode(TailorProposal proposal) {
        if (proposal == null || isBlank(proposal.getMode())) {
            return TailorMode.AGGRESSIVE;
        }
        try {
            return TailorMode.valueOf(proposal.getMode());
        } catch (IllegalArgumentException ex) {
            return TailorMode.AGGRESSIVE;
        }
    }

    private record TailorContext(
            User user,
            Job job,
            Resume resume,
            Path originalPath,
            List<ResumeDocumentNode> nodes,
            String jobDescription
    ) {
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

    private TailoredResumeResponse createPendingProposal(
            Long userId,
            Long resumeId,
            Long jobId,
            TailorMode mode,
            List<TailorChange> accepted,
            List<RejectedTailorChange> rejected,
            List<String> exploredNodeIds,
            List<ResumeDocumentNode> nodes,
            String jobDescription) {
        return createPendingProposal(
                userId, resumeId, jobId, mode, accepted, rejected, exploredNodeIds, nodes, jobDescription, null);
    }

    private TailoredResumeResponse createPendingProposal(
            Long userId,
            Long resumeId,
            Long jobId,
            TailorMode mode,
            List<TailorChange> accepted,
            List<RejectedTailorChange> rejected,
            List<String> exploredNodeIds,
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            ConservativeRunResult coverage) {

        for (TailorChange change : accepted) {
            change.setApplied(false);
        }

        LinkedHashSet<String> explored = new LinkedHashSet<>();
        if (exploredNodeIds != null) {
            explored.addAll(exploredNodeIds);
        }
        TailorProposalState state = TailorProposalState.fromChanges(accepted, new ArrayList<>(explored));
        applyCoverage(state, coverage, nodes, jobDescription, accepted);
        boolean hasMore = Boolean.TRUE.equals(state.getHasMoreSuggestions());

        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(PROPOSAL_TTL_HOURS);
        TailorProposal proposal = new TailorProposal();
        proposal.setUserId(userId);
        proposal.setResumeId(resumeId);
        proposal.setJobId(jobId);
        proposal.setMode(mode.name());
        proposal.setStatus(TailorProposal.STATUS_PENDING);
        proposal.setExpiresAt(expiresAt);
        proposal.setChangesJson(writeJson(state));
        proposal.setRejectedJson(writeJson(rejected));
        tailorProposalMapper.insert(proposal);

        return toTailoredResponse(
                resumeId,
                jobId,
                mode,
                null,
                null,
                null,
                null,
                accepted,
                0,
                rejected,
                proposal.getId(),
                TailorProposal.STATUS_PENDING,
                expiresAt,
                0,
                hasMore,
                state.getExploredNodeIds(),
                state.getEligibleNodeIds(),
                state.getRemainingNodeIds(),
                state.getNoChangeNodeIds(),
                state.getCoverageStatus()
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
        if (TailorChange.EVIDENCE_USER_EDITED.equals(evidenceType)) {
            if (change.getNewText().strip().length() > ConservativeTailorPlanner.ABSOLUTE_MAX_CHARACTERS) {
                return RejectedTailorChange.TEXT_TOO_LONG;
            }
            return null;
        }
        if (TailorChange.EVIDENCE_VERIFIED.equals(evidenceType)) {
            return validateVerifiedEvidence(change, nodesById, null);
        }
        return null;
    }

    private List<EditedTailorChange> normalizeEditedChanges(
            List<EditedTailorChange> editedChanges,
            Set<String> selected) {

        if (editedChanges == null || editedChanges.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<EditedTailorChange> normalized = new ArrayList<>();
        for (EditedTailorChange edit : editedChanges) {
            if (edit == null) {
                continue;
            }
            if (isBlank(edit.getNodeId())) {
                throw new InvalidTailorProposalException("editedChanges nodeId is required");
            }
            if (!seen.add(edit.getNodeId())) {
                throw new InvalidTailorProposalException("duplicate nodeId");
            }
            if (!selected.contains(edit.getNodeId())) {
                throw new InvalidTailorProposalException("edited nodeId must be selected");
            }
            String newText = edit.getNewText() == null ? "" : edit.getNewText().strip();
            if (newText.isEmpty()) {
                throw new InvalidTailorProposalException("newText must not be blank");
            }
            if (newText.length() > ConservativeTailorPlanner.ABSOLUTE_MAX_CHARACTERS) {
                throw new InvalidTailorProposalException("newText is too long");
            }
            normalized.add(new EditedTailorChange(edit.getNodeId(), newText));
        }
        return List.copyOf(normalized);
    }

    private void applyUserEdits(
            List<TailorChange> stored,
            Set<String> selected,
            List<EditedTailorChange> edits,
            List<ResumeDocumentNode> nodes) {

        if (edits == null || edits.isEmpty()) {
            return;
        }
        Map<String, TailorChange> byId = new LinkedHashMap<>();
        if (stored != null) {
            for (TailorChange change : stored) {
                if (change != null && !isBlank(change.getNodeId())) {
                    byId.putIfAbsent(change.getNodeId(), change);
                }
            }
        }
        Map<String, ResumeDocumentNode> nodesById = new LinkedHashMap<>();
        for (ResumeDocumentNode node : nodes == null ? List.<ResumeDocumentNode>of() : nodes) {
            nodesById.put(node.getNodeId(), node);
        }
        Set<String> allowedIds = ResumeTailorNodeGuard.allowedNodeIds(nodes);

        for (EditedTailorChange edit : edits) {
            TailorChange storedChange = byId.get(edit.getNodeId());
            if (storedChange == null) {
                throw new InvalidTailorProposalException("edited nodeId is not part of this proposal");
            }
            if (!selected.contains(edit.getNodeId())) {
                throw new InvalidTailorProposalException("edited nodeId must be selected");
            }
            ResumeDocumentNode node = nodesById.get(edit.getNodeId());
            if (node == null) {
                throw new InvalidTailorProposalException("edited nodeId is not part of this proposal");
            }
            if (!allowedIds.contains(edit.getNodeId())) {
                throw new InvalidTailorProposalException("node cannot be edited");
            }
            if (!normalizeNodeText(node.getText())
                    .equals(normalizeNodeText(storedChange.getOriginalText()))) {
                throw new InvalidTailorProposalException("edited node does not match the target node");
            }
            storedChange.setNewText(edit.getNewText());
            storedChange.setEvidenceType(TailorChange.EVIDENCE_USER_EDITED);
        }
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

    private void throwContinueUnavailable(Long proposalId, Long userId, Long jobId) {
        TailorProposal proposal = tailorProposalMapper.findById(proposalId);
        if (proposal == null
                || !userId.equals(proposal.getUserId())
                || !jobId.equals(proposal.getJobId())) {
            throw new TailorProposalNotFoundException("Tailor proposal not found");
        }
        if (TailorProposal.STATUS_CONFIRMING.equals(proposal.getStatus()) && !isExpired(proposal)) {
            throw new TailorProposalConflictException(
                    "Tailor proposal is already being confirmed"
            );
        }
        if (TailorProposal.STATUS_CONFIRMED.equals(proposal.getStatus()) || isExpired(proposal)
                || !TailorProposal.STATUS_PENDING.equals(proposal.getStatus())) {
            throw new TailorProposalConflictException("Tailor proposal is no longer pending");
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
        return readProposalState(json).getChanges();
    }

    private TailorProposalState readProposalState(String json) {
        return TailorProposalState.parse(objectMapper, json);
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
            String generatedFilePath,
            int addedSuggestionCount) {

        TailorProposalState state = readProposalState(proposal.getChangesJson());
        List<TailorChange> changes = state.getChanges();
        for (TailorChange change : changes) {
            if (appliedChangeCount == 0) {
                change.setApplied(false);
            }
        }
        boolean hasMore = Boolean.TRUE.equals(state.getHasMoreSuggestions());
        TailoredResumeResponse response = toTailoredResponse(
                proposal.getResumeId(),
                proposal.getJobId(),
                proposalMode(proposal),
                generatedFileName,
                generatedFilePath,
                null,
                null,
                changes,
                appliedChangeCount,
                rejected,
                proposal.getId(),
                proposal.getStatus(),
                proposal.getExpiresAt(),
                addedSuggestionCount,
                hasMore,
                state.getExploredNodeIds(),
                state.getEligibleNodeIds(),
                state.getRemainingNodeIds(),
                state.getNoChangeNodeIds(),
                state.getCoverageStatus()
        );
        applyEnhancedEnvelope(response, state);
        return response;
    }

    private TailoredResumeResponse toTailoredResponse(
            Long resumeId,
            Long jobId,
            TailorMode mode,
            String generatedFileName,
            String generatedFilePath,
            String pdfDownloadUrl,
            PdfExportStatus pdfExportStatus,
            List<TailorChange> changes,
            int appliedChangeCount,
            List<RejectedTailorChange> rejected,
            Long proposalId,
            String status,
            LocalDateTime expiresAt,
            int addedSuggestionCount,
            Boolean hasMoreSuggestions,
            List<String> exploredNodeIds) {
        return toTailoredResponse(
                resumeId, jobId, mode, generatedFileName, generatedFilePath, pdfDownloadUrl, pdfExportStatus,
                changes, appliedChangeCount, rejected, proposalId, status, expiresAt, addedSuggestionCount,
                hasMoreSuggestions, exploredNodeIds, null, null, null, null);
    }

    private TailoredResumeResponse toTailoredResponse(
            Long resumeId,
            Long jobId,
            TailorMode mode,
            String generatedFileName,
            String generatedFilePath,
            String pdfDownloadUrl,
            PdfExportStatus pdfExportStatus,
            List<TailorChange> changes,
            int appliedChangeCount,
            List<RejectedTailorChange> rejected,
            Long proposalId,
            String status,
            LocalDateTime expiresAt,
            int addedSuggestionCount,
            Boolean hasMoreSuggestions,
            List<String> exploredNodeIds,
            List<String> eligibleNodeIds,
            List<String> remainingNodeIds,
            List<String> noChangeNodeIds,
            String coverageStatus) {

        String downloadUrl = isBlank(generatedFileName)
                ? null
                : "/api/v1/jobs/" + jobId + "/tailored/" + generatedFileName;

        boolean hasSuggestions = changes != null && !changes.isEmpty();
        return new TailoredResumeResponse(
                resumeId,
                jobId,
                mode,
                generatedFileName,
                generatedFilePath,
                downloadUrl,
                pdfDownloadUrl,
                pdfExportStatus,
                changes,
                appliedChangeCount == 0,
                hasSuggestions,
                appliedChangeCount,
                rejected == null ? 0 : rejected.size(),
                rejected == null ? List.of() : rejected,
                proposalId,
                status,
                expiresAt,
                addedSuggestionCount,
                hasMoreSuggestions,
                exploredNodeIds,
                eligibleNodeIds,
                remainingNodeIds,
                noChangeNodeIds,
                coverageStatus,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private PdfExportFields exportTailoredPdf(Long jobId, Path generatedDocx) {
        try {
            Path pdf = pdfExportService.exportPdf(generatedDocx);
            if (pdf == null || pdf.getFileName() == null) {
                return PdfExportFields.failed();
            }
            String pdfName = pdf.getFileName().toString();
            if (!pdfName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                return PdfExportFields.failed();
            }
            return new PdfExportFields(
                    "/api/v1/jobs/" + jobId + "/tailored/" + pdfName,
                    PdfExportStatus.SUCCESS
            );
        } catch (RuntimeException ex) {
            log.warn("PDF export failed: {}", ex.getClass().getSimpleName());
            return PdfExportFields.failed();
        }
    }

    private record PdfExportFields(String pdfDownloadUrl, PdfExportStatus status) {
        static PdfExportFields failed() {
            return new PdfExportFields(null, PdfExportStatus.FAILED);
        }
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

    record ConservativeRunResult(
            List<TailorChange> accepted,
            List<RejectedTailorChange> rejected,
            String status,
            List<String> exploredNodeIds,
            List<String> eligibleNodeIds,
            List<String> remainingNodeIds,
            List<String> noChangeNodeIds,
            String coverageStatus) {
    }

    ConservativeRunResult runConservativeTailor(
            List<ResumeDocumentNode> nodes,
            String jobDescription) {
        return runCoveragePage(nodes, jobDescription, List.of(), Set.of());
    }

    ConservativeRunResult runConservativeContinue(
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            List<TailorChange> lockedChanges,
            List<String> exploredNodeIds) {

        Set<String> excluded = continueExclusions(nodes, lockedChanges, exploredNodeIds);
        return runCoveragePage(nodes, jobDescription, lockedChanges, excluded);
    }

    private ConservativeRunResult runCoveragePage(
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            List<TailorChange> lockedChanges,
            Set<String> excludedNodeIds) {

        ClaimLedger ledger = ClaimLedger.from(nodes);
        ConservativeTailorPlanner.CoveragePage page =
                ConservativeTailorPlanner.nextPage(nodes, jobDescription, excludedNodeIds);
        List<ConservativeRewritePlan> plans = page.generationPlans().stream()
                .map(plan -> withLedgerFacts(plan, ledger))
                .toList();
        List<String> explored = new ArrayList<>(page.evaluatedNodeIds());
        LinkedHashSet<String> noChange = new LinkedHashSet<>(page.noChangeNodeIds());
        Set<String> reserved = new LinkedHashSet<>(explored);
        if (lockedChanges != null) {
            for (TailorChange change : lockedChanges) {
                if (change != null && change.getNodeId() != null && !change.getNodeId().isBlank()) {
                    reserved.add(change.getNodeId());
                }
            }
        }
        List<RejectedTailorChange> rejected = new ArrayList<>();
        List<TailorChange> accepted = new ArrayList<>();
        if (!plans.isEmpty()) {
            List<ConservativeBatchSelector.PlanPick> admittedPool =
                    admitPlans(plans, nodes, jobDescription, ledger, rejected, reserved);
            ConservativeBatchSelector.Selection batch;
            if (lockedChanges == null || lockedChanges.isEmpty()) {
                batch = ConservativeBatchSelector.select(
                        admittedPool,
                        new ConservativeBatchSelector.Context(List.of(), reserved, Set.of()));
                batch = FinalResumeCoherenceValidator.prune(nodes, batch, List.of());
            } else {
                Map<String, String> lockedEntries = new LinkedHashMap<>();
                for (TailorChange change : lockedChanges) {
                    if (change == null || change.getNodeId() == null) {
                        continue;
                    }
                    lockedEntries.put(
                            change.getNodeId(),
                            ConservativeEvidencePacket.build(change.getNodeId(), ledger).entryKey()
                    );
                }
                Set<String> lockedSupporting = lockedSupportingFacts(lockedChanges, ledger);
                batch = ConservativeContinueSelector.select(
                        lockedChanges, lockedEntries, admittedPool, reserved, lockedSupporting);
                batch = FinalResumeCoherenceValidator.prune(nodes, batch, lockedChanges);
            }
            batch = ConservativeLanguageJudge.apply(aiResumeService, jobDescription, batch);
            rejected.addAll(batch.dropped());
            for (ConservativeBatchSelector.PlanPick pick : batch.selected()) {
                accepted.add(pick.change());
            }
        }
        Set<String> acceptedIds = new LinkedHashSet<>();
        for (TailorChange change : accepted) {
            if (change != null && change.getNodeId() != null) {
                acceptedIds.add(change.getNodeId());
            }
        }
        for (ConservativeRewritePlan plan : plans) {
            if (plan != null && plan.nodeId() != null && !acceptedIds.contains(plan.nodeId())) {
                noChange.add(plan.nodeId());
            }
        }
        sortSuggestions(accepted);
        String coverage = page.remainingNodeIds().isEmpty()
                ? TailorProposalState.COVERAGE_COMPLETE
                : TailorProposalState.COVERAGE_RUNNING;
        String status = accepted.isEmpty()
                ? conservativeStatus(accepted, rejected)
                : TailoredResumeResponse.STATUS_APPLIED;
        return new ConservativeRunResult(
                accepted,
                rejected,
                status,
                explored,
                page.eligibleNodeIds(),
                page.remainingNodeIds(),
                new ArrayList<>(noChange),
                coverage
        );
    }

    private List<ConservativeBatchSelector.PlanPick> admitPlans(
            List<ConservativeRewritePlan> plans,
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            ClaimLedger ledger,
            List<RejectedTailorChange> rejected,
            Set<String> reservedTargetNodeIds) {

        Map<String, ResumeDocumentNode> nodesById = new LinkedHashMap<>();
        for (ResumeDocumentNode node : nodes) {
            nodesById.put(node.getNodeId(), node);
        }
        List<ConservativeBatchSelector.PlanPick> admittedPool = new ArrayList<>();
        for (ConservativeRewritePlan plan : plans) {
            ResumeDocumentNode node = nodesById.get(plan.nodeId());
            if (node == null) {
                rejected.add(new RejectedTailorChange(
                        plan.nodeId(),
                        RejectedTailorChange.UNKNOWN_NODE_ID
                ));
                continue;
            }

            List<ConservativeRewriteCandidate> candidates;
            try {
                candidates = loadCandidates(node, plan, jobDescription, null);
            } catch (RuntimeException ex) {
                log.warn("Candidate generation failed for {}: {}", plan.nodeId(), ex.getClass().getSimpleName());
                rejected.add(new RejectedTailorChange(
                        plan.nodeId(),
                        RejectedTailorChange.EMPTY_REQUIRED_FIELD));
                continue;
            }
            AdmitResult admitted = admitCandidates(
                    nodes, node, plan, jobDescription, candidates, ledger, reservedTargetNodeIds);
            if (admitted.picks().isEmpty() && isRepairable(admitted.lastReason())) {
                List<ConservativeRewriteCandidate> repaired;
                try {
                    repaired = loadCandidates(
                            node,
                            plan,
                            jobDescription,
                            new ConservativeRewriteRepair(admitted.lastReason(), firstCandidateText(candidates))
                    );
                } catch (RuntimeException ex) {
                    log.warn("Candidate repair failed for {}: {}", plan.nodeId(), ex.getClass().getSimpleName());
                    repaired = List.of();
                }
                admitted = admitCandidates(
                        nodes, node, plan, jobDescription, repaired, ledger, reservedTargetNodeIds);
            }
            if (admitted.picks().isEmpty()) {
                rejected.add(new RejectedTailorChange(
                        plan.nodeId(),
                        admitted.lastReason() == null
                                ? RejectedTailorChange.EMPTY_REQUIRED_FIELD
                                : admitted.lastReason()
                ));
                continue;
            }
            admittedPool.addAll(admitted.picks());
        }
        return admittedPool;
    }

    private void applyCoverage(
            TailorProposalState state,
            ConservativeRunResult coverage,
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            List<TailorChange> changes) {

        List<String> eligible = coverage != null && coverage.eligibleNodeIds() != null
                && !coverage.eligibleNodeIds().isEmpty()
                ? coverage.eligibleNodeIds()
                : OpportunityDetector.eligibleNodeIds(nodes);
        state.setEligibleNodeIds(new ArrayList<>(new LinkedHashSet<>(eligible)));
        LinkedHashSet<String> noChange = new LinkedHashSet<>();
        if (state.getNoChangeNodeIds() != null) {
            noChange.addAll(state.getNoChangeNodeIds());
        }
        if (coverage != null && coverage.noChangeNodeIds() != null) {
            noChange.addAll(coverage.noChangeNodeIds());
        }
        state.setNoChangeNodeIds(new ArrayList<>(noChange));
        LinkedHashSet<String> remaining = new LinkedHashSet<>(state.getEligibleNodeIds());
        if (state.getExploredNodeIds() != null) {
            remaining.removeAll(state.getExploredNodeIds());
        }
        state.setRemainingNodeIds(new ArrayList<>(remaining));
        boolean hasMore = !remaining.isEmpty();
        state.setHasMoreSuggestions(hasMore);
        state.setCoverageStatus(hasMore
                ? TailorProposalState.COVERAGE_RUNNING
                : TailorProposalState.COVERAGE_COMPLETE);
    }

    private boolean hasMoreSuggestions(
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            List<TailorChange> lockedChanges,
            List<String> exploredNodeIds) {

        LinkedHashSet<String> eligible = new LinkedHashSet<>(OpportunityDetector.eligibleNodeIds(nodes));
        LinkedHashSet<String> done = new LinkedHashSet<>();
        if (exploredNodeIds != null) {
            done.addAll(exploredNodeIds);
        }
        if (lockedChanges != null) {
            for (TailorChange change : lockedChanges) {
                if (change != null && change.getNodeId() != null && !change.getNodeId().isBlank()) {
                    done.add(change.getNodeId());
                }
            }
        }
        return !done.containsAll(eligible);
    }

    private static void sortSuggestions(List<TailorChange> changes) {
        if (changes == null || changes.size() < 2) {
            return;
        }
        changes.sort(Comparator
                .comparingInt(JobServiceImpl::suggestionDisplayRank)
                .thenComparing(change -> change.getNodeId() == null ? "" : change.getNodeId()));
    }

    private static int suggestionDisplayRank(TailorChange change) {
        String opportunity = change == null || change.getOpportunityType() == null
                ? ""
                : change.getOpportunityType();
        String uplift = change == null || change.getUpliftType() == null ? "" : change.getUpliftType();
        if (OpportunityDetector.JD_RELEVANCE.equals(opportunity)
                || UpliftType.SUBSTANTIVE_REWRITE.equals(uplift)
                || RewriteIntent.JD_RELEVANCE.equals(uplift)
                || RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT.equals(change == null ? "" : change.getUpliftType())) {
            return 1;
        }
        if (OpportunityDetector.TENSE.equals(opportunity)
                || OpportunityDetector.GRAMMAR.equals(opportunity)
                || UpliftType.GRAMMAR_FIX.equals(uplift)) {
            return 2;
        }
        if (OpportunityDetector.CLARITY.equals(opportunity)
                || OpportunityDetector.SPECIFICITY.equals(opportunity)
                || UpliftType.CLARITY_IMPROVEMENT.equals(uplift)) {
            return 3;
        }
        if (OpportunityDetector.CONCISION.equals(opportunity)
                || OpportunityDetector.PROFESSIONAL_LANGUAGE.equals(opportunity)) {
            return 4;
        }
        return 5;
    }

    private Set<String> continueExclusions(
            List<ResumeDocumentNode> nodes,
            List<TailorChange> lockedChanges,
            List<String> exploredNodeIds) {

        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        if (exploredNodeIds != null) {
            excluded.addAll(exploredNodeIds);
        }
        if (lockedChanges != null) {
            for (TailorChange change : lockedChanges) {
                if (change != null && change.getNodeId() != null && !change.getNodeId().isBlank()) {
                    excluded.add(change.getNodeId());
                }
            }
        }
        return excluded;
    }

    private Set<String> lockedSupportingFacts(List<TailorChange> lockedChanges, ClaimLedger ledger) {
        LinkedHashSet<String> used = new LinkedHashSet<>();
        if (lockedChanges == null) {
            return used;
        }
        for (TailorChange change : lockedChanges) {
            if (change == null || change.getNodeId() == null || change.getNodeId().isBlank()) {
                continue;
            }
            ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(change.getNodeId(), ledger);
            used.addAll(ConservativeCandidateIntent.resolveSupporting(
                    change.getOriginalText(),
                    change.getNewText(),
                    packet,
                    ledger));
            if (change.getEvidenceNodeIds() != null) {
                for (String id : change.getEvidenceNodeIds()) {
                    if (id != null && !id.isBlank() && !id.equals(change.getNodeId())) {
                        used.add(id);
                    }
                }
            }
        }
        return used;
    }

    private static List<String> planNodeIds(List<ConservativeRewritePlan> plans) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (plans == null) {
            return List.of();
        }
        for (ConservativeRewritePlan plan : plans) {
            if (plan != null && plan.nodeId() != null && !plan.nodeId().isBlank()) {
                ids.add(plan.nodeId());
            }
        }
        return new ArrayList<>(ids);
    }

    private ConservativeRewritePlan withLedgerFacts(ConservativeRewritePlan plan, ClaimLedger ledger) {
        return ConservativeEvidencePacket.ensurePacket(plan, ledger);
    }

    private List<ConservativeRewriteCandidate> loadCandidates(
            ResumeDocumentNode node,
            ConservativeRewritePlan plan,
            String jobDescription,
            ConservativeRewriteRepair repair) {

        ConservativeRewriteCandidatesResponse response =
                aiResumeService.generateConstrainedTailorCandidates(node, plan, jobDescription, repair);
        if (response == null || response.getCandidates() == null || response.getCandidates().isEmpty()) {
            return List.of();
        }
        return response.getCandidates();
    }

    private AdmitResult admitCandidates(
            List<ResumeDocumentNode> nodes,
            ResumeDocumentNode node,
            ConservativeRewritePlan plan,
            String jobDescription,
            List<ConservativeRewriteCandidate> candidates,
            ClaimLedger ledger,
            Set<String> reservedTargetNodeIds) {

        if (candidates == null || candidates.isEmpty()) {
            return new AdmitResult(List.of(), RejectedTailorChange.EMPTY_REQUIRED_FIELD);
        }
        ConservativeEvidencePacket packet = new ConservativeEvidencePacket(
                plan.targetNodeId() == null || plan.targetNodeId().isBlank() ? plan.nodeId() : plan.targetNodeId(),
                plan.entryKey(),
                plan.requiredFacts(),
                plan.availableSupportingFacts(),
                plan.supportingNodeIds(),
                plan.forbiddenClaims(),
                plan.maxCharacters()
        );
        List<ConservativeBatchSelector.PlanPick> admitted = new ArrayList<>();
        String lastReason = RejectedTailorChange.EMPTY_REQUIRED_FIELD;
        for (ConservativeRewriteCandidate candidate : candidates) {
            if (candidate == null || isBlank(candidate.getNewText())) {
                lastReason = RejectedTailorChange.EMPTY_REQUIRED_FIELD;
                continue;
            }
            ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                    candidate, node, plan, packet, ledger, jobDescription);
            if (!gate.passed()) {
                lastReason = gate.reasonCode();
                continue;
            }
            String opportunityReject = OpportunityResolutionGate.rejectReason(
                    node.getText(), candidate.getNewText(), plan, packet, ledger);
            if (opportunityReject != null) {
                lastReason = opportunityReject;
                continue;
            }
            String reservedReject = ConservativeReservedTargetPolicy.absorbReason(
                    node.getNodeId(),
                    node.getText(),
                    candidate.getNewText(),
                    reservedTargetNodeIds,
                    nodes);
            if (reservedReject != null) {
                lastReason = reservedReject;
                continue;
            }
            List<String> usedFacts = withTarget(node.getNodeId(), gate.usedSupportingNodeIds());
            TailorChange proposed = new TailorChange();
            proposed.setNewText(candidate.getNewText());
            proposed.setReason(candidate.getReason());
            NodeAttempt attempt = validateConservativeChange(nodes, node, proposed, ledger, usedFacts, plan);
            if (attempt.accepted() == null) {
                lastReason = attempt.reasonCode();
                continue;
            }
            ConservativeCandidateLocalScorer.Score localScore = ConservativeCandidateLocalScorer.score(
                    node, plan, packet, ledger, jobDescription, attempt.accepted().getNewText(),
                    gate.usedSupportingNodeIds(), gate.quality());
            if (!localScore.admitted()) {
                lastReason = localScore.rejectReason() == null
                        ? RejectedTailorChange.LOW_VALUE_REWRITE
                        : localScore.rejectReason();
                continue;
            }
            TailorChange accepted = new TailorChange();
            accepted.setNewText(candidate.getNewText());
            applyServerEvidence(accepted, node, ledger, usedFacts);
            List<String> supportingTexts = new ArrayList<>();
            if (gate.usedSupportingNodeIds() != null) {
                for (String supportingId : gate.usedSupportingNodeIds()) {
                    supportingTexts.add(ConservativeCandidateHardGate.textOf(ledger, supportingId));
                }
            }
            ConservativeRewriteReasons.apply(accepted, localScore.classifiedIntent(), supportingTexts);
            accepted.setUpliftType(localScore.upliftType());
            accepted.setOpportunityType(plan == null ? null : plan.rankingReason());
            accepted.setApplied(Boolean.TRUE);
            admitted.add(new ConservativeBatchSelector.PlanPick(plan, accepted, localScore, usedFacts));
        }
        return new AdmitResult(List.copyOf(admitted), lastReason);
    }

    private static List<String> withTarget(String targetNodeId, List<String> supporting) {
        LinkedHashSet<String> used = new LinkedHashSet<>();
        if (targetNodeId != null && !targetNodeId.isBlank()) {
            used.add(targetNodeId);
        }
        if (supporting != null) {
            used.addAll(supporting);
        }
        return List.copyOf(used);
    }

    private NodeAttempt validateConservativeChange(
            List<ResumeDocumentNode> nodes,
            ResumeDocumentNode node,
            TailorChange proposed,
            ClaimLedger ledger,
            List<String> usedFactNodeIds) {
        return validateConservativeChange(nodes, node, proposed, ledger, usedFactNodeIds, null);
    }

    private NodeAttempt validateConservativeChange(
            List<ResumeDocumentNode> nodes,
            ResumeDocumentNode node,
            TailorChange proposed,
            ClaimLedger ledger,
            List<String> usedFactNodeIds,
            ConservativeRewritePlan plan) {

        TailorChange change = proposed == null ? new TailorChange() : proposed;
        applyServerEvidence(change, node, ledger, usedFactNodeIds);

        Integer maxCharacters = plan != null && plan.maxCharacters() > 0
                ? Math.min(plan.maxCharacters(), ConservativeTailorPlanner.ABSOLUTE_MAX_CHARACTERS)
                : null;
        TailorFilterResult filtered =
                filterSafeChanges(nodes, List.of(change), TailorMode.CONSERVATIVE, maxCharacters);
        if (!filtered.rejected().isEmpty()) {
            return new NodeAttempt(null, filtered.rejected().get(0).getReasonCode(), List.of());
        }
        if (filtered.accepted().isEmpty()) {
            return new NodeAttempt(null, RejectedTailorChange.EMPTY_REQUIRED_FIELD, List.of());
        }

        TailorChange accepted = filtered.accepted().get(0);
        if (!ledger.coversTech(accepted.getNewText(), node.getNodeId())
                || !ledger.coversNumbers(accepted.getNewText(), node.getNodeId())) {
            return new NodeAttempt(null, RejectedTailorChange.UNSUPPORTED_CLAIM, List.of());
        }
        return new NodeAttempt(accepted, null, usedFactNodeIds);
    }

    static void applyServerEvidence(
            TailorChange change,
            ResumeDocumentNode node,
            ClaimLedger ledger,
            List<String> usedFactNodeIds) {

        if (change == null || node == null) {
            return;
        }
        change.setNodeId(node.getNodeId());
        change.setOriginalText(node.getText());
        List<String> evidenceIds = usedFactNodeIds == null || usedFactNodeIds.isEmpty()
                ? List.of(node.getNodeId())
                : List.copyOf(usedFactNodeIds);
        if (!evidenceIds.contains(node.getNodeId())) {
            List<String> withTarget = new ArrayList<>();
            withTarget.add(node.getNodeId());
            withTarget.addAll(evidenceIds);
            evidenceIds = List.copyOf(withTarget);
        }
        change.setEvidenceNodeIds(evidenceIds);
        change.setEvidenceQuote(evidenceQuoteFor(node, ledger, evidenceIds));
        change.setEvidenceType(TailorChange.EVIDENCE_VERIFIED);
        change.setRequiresConfirmation(false);
        change.setInferredSkill(null);
    }

    private static String evidenceQuoteFor(
            ResumeDocumentNode node,
            ClaimLedger ledger,
            List<String> evidenceIds) {

        if (ledger == null) {
            return node.getText();
        }
        for (String evidenceId : evidenceIds) {
            if (node.getNodeId().equals(evidenceId)) {
                continue;
            }
            ClaimLedger.Entry entry = ledger.entryOf(node.getNodeId());
            if (entry == null) {
                break;
            }
            for (ClaimLedger.Fact fact : entry.facts()) {
                if (evidenceId.equals(fact.sourceNodeId()) && fact.factText() != null && !fact.factText().isBlank()) {
                    String quote = fact.factText().strip();
                    return quote.length() > 180 ? quote.substring(0, 180) : quote;
                }
            }
        }
        return node.getText();
    }

    static boolean isRepairable(String reasonCode) {
        return RejectedTailorChange.TEXT_TOO_LONG.equals(reasonCode)
                || RejectedTailorChange.TEXT_UNCHANGED.equals(reasonCode)
                || RejectedTailorChange.LOW_VALUE_REWRITE.equals(reasonCode)
                || RejectedTailorChange.OPPORTUNITY_NOT_RESOLVED.equals(reasonCode)
                || RejectedTailorChange.FACT_CARDINALITY_CHANGED.equals(reasonCode)
                || RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(reasonCode)
                || RejectedTailorChange.INFORMATION_LOSS.equals(reasonCode)
                || RejectedTailorChange.GRAMMAR_QUALITY_FAILED.equals(reasonCode)
                || RejectedTailorChange.CROSS_NODE_EVIDENCE_NOT_ALLOWED.equals(reasonCode)
                || RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE.equals(reasonCode)
                || RejectedTailorChange.UNSUPPORTED_ACTION.equals(reasonCode);
    }

    private String conservativeStatus(
            List<TailorChange> accepted,
            List<RejectedTailorChange> rejected) {

        if (!accepted.isEmpty()) {
            return TailoredResumeResponse.STATUS_APPLIED;
        }
        if (rejected.stream().anyMatch(item ->
                RejectedTailorChange.UNSUPPORTED_CLAIM.equals(item.getReasonCode())
                        || RejectedTailorChange.MISSING_EVIDENCE.equals(item.getReasonCode())
                        || RejectedTailorChange.EVIDENCE_QUOTE_MISMATCH.equals(item.getReasonCode())
                        || RejectedTailorChange.CROSS_NODE_EVIDENCE_NOT_ALLOWED.equals(item.getReasonCode())
                        || RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED.equals(item.getReasonCode())
                        || RejectedTailorChange.UNKNOWN_EVIDENCE_NODE_ID.equals(item.getReasonCode()))) {
            return TailoredResumeResponse.STATUS_NEEDS_USER_EVIDENCE;
        }
        if (rejected.isEmpty()) {
            return TailoredResumeResponse.STATUS_NO_SUPPORTED_GAP;
        }
        return TailoredResumeResponse.STATUS_GENERATION_REJECTED;
    }

    private String firstCandidateText(List<ConservativeRewriteCandidate> candidates) {
        if (candidates == null || candidates.isEmpty() || candidates.get(0) == null) {
            return null;
        }
        return candidates.get(0).getNewText();
    }

    private record AdmitResult(
            List<ConservativeBatchSelector.PlanPick> picks,
            String lastReason
    ) {
    }

    private record NodeAttempt(
            TailorChange accepted,
            String reasonCode,
            List<String> usedFactNodeIds,
            ConservativeCandidateLocalScorer.Score score
    ) {
        private NodeAttempt(TailorChange accepted, String reasonCode, List<String> usedFactNodeIds) {
            this(accepted, reasonCode, usedFactNodeIds, null);
        }

        private NodeAttempt(TailorChange accepted, String reasonCode) {
            this(accepted, reasonCode, List.of(), null);
        }
    }

    TailorFilterResult filterSafeChanges(
            List<ResumeDocumentNode> nodes,
            List<TailorChange> proposed,
            TailorMode mode) {
        return filterSafeChanges(nodes, proposed, mode, null);
    }

    TailorFilterResult filterSafeChanges(
            List<ResumeDocumentNode> nodes,
            List<TailorChange> proposed,
            TailorMode mode,
            Integer maxCharacters) {

        Map<String, ResumeDocumentNode> nodesById = new LinkedHashMap<>();
        for (ResumeDocumentNode node : nodes) {
            nodesById.put(node.getNodeId(), node);
        }

        Set<String> allowedIds = ResumeTailorNodeGuard.allowedNodeIds(nodes);
        ClaimLedger ledger = ClaimLedger.from(nodes);
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

            boolean tooLong = maxCharacters != null
                    ? change.getNewText().strip().length() > Math.min(
                    maxCharacters, ConservativeTailorPlanner.ABSOLUTE_MAX_CHARACTERS)
                    : exceedsLengthLimit(change.getOriginalText(), change.getNewText(), maxLengthRatio);
            if (tooLong) {
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
                String evidenceReason = validateVerifiedEvidence(change, nodesById, mode, ledger);
                if (evidenceReason != null) {
                    rejected.add(new RejectedTailorChange(change.getNodeId(), evidenceReason));
                    continue;
                }
                if (mode == TailorMode.CONSERVATIVE
                        && (hasUnsupportedClaim(
                                change.getNewText(),
                                conservativeEvidenceNodes(ledger, nodes, change))
                        || !ledger.coversTech(change.getNewText(), change.getNodeId())
                        || !ledger.coversNumbers(change.getNewText(), change.getNodeId()))) {
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

            if (accepted.size() >= MAX_VERIFIED_WRITES && mode != TailorMode.CONSERVATIVE) {
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
        if (TailorChange.EVIDENCE_USER_EDITED.equalsIgnoreCase(evidenceType)) {
            return TailorChange.EVIDENCE_USER_EDITED;
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String validateVerifiedEvidence(
            TailorChange change,
            Map<String, ResumeDocumentNode> nodesById,
            TailorMode mode) {
        return validateVerifiedEvidence(change, nodesById, mode, null);
    }

    private String validateVerifiedEvidence(
            TailorChange change,
            Map<String, ResumeDocumentNode> nodesById,
            TailorMode mode,
            ClaimLedger ledger) {

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

        if (mode == TailorMode.CONSERVATIVE) {
            for (String evidenceNodeId : evidenceNodeIds) {
                if (change.getNodeId().equals(evidenceNodeId)) {
                    continue;
                }
                if (ledger == null || !ledger.sameEntry(change.getNodeId(), evidenceNodeId)) {
                    return RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED;
                }
            }
        }

        String cited = normalizeNodeText(citedText.toString());
        String quote = normalizeNodeText(change.getEvidenceQuote());
        if (quote.isEmpty() || !cited.contains(quote)) {
            return RejectedTailorChange.EVIDENCE_QUOTE_MISMATCH;
        }
        return null;
    }

    private List<ResumeDocumentNode> conservativeEvidenceNodes(
            ClaimLedger ledger,
            List<ResumeDocumentNode> nodes,
            TailorChange change) {

        List<ResumeDocumentNode> entryNodes = ledger.nodesInEntry(change.getNodeId());
        if (!entryNodes.isEmpty()) {
            return entryNodes;
        }
        for (ResumeDocumentNode node : nodes) {
            if (change.getNodeId().equals(node.getNodeId())) {
                return List.of(node);
            }
        }
        return List.of();
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
        if (phrase.contains(" ") || phrase.contains("/")) {
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
                job.isApplied(),
                job.getAppliedAt(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
