package aijob.service;

import aijob.dto.BulkDeleteJobsResponse;
import aijob.dto.CreateJobRequest;
import aijob.dto.AnswerEnhancedQuestionRequest;
import aijob.dto.AnswerEnhancedQuestionResponse;
import aijob.dto.ConfirmTailorProposalRequest;
import aijob.dto.EditedTailorChange;
import aijob.dto.ImportJobsResponse;
import aijob.dto.JobMatchResponse;
import aijob.dto.JobResponse;
import aijob.dto.JobSearchRequest;
import aijob.dto.JobSearchResponse;
import aijob.dto.SaveJobSearchResultRequest;
import aijob.dto.SaveJobSearchResultResponse;
import aijob.dto.ImportSearchResultsRequest;
import aijob.dto.ImportSearchResultsResponse;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.UpdateJobRequest;
import aijob.enums.TailorMode;
import aijob.tailor.v3.V3OpportunityMap;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public interface JobService {

    JobResponse createJob(String email, CreateJobRequest request);

    List<JobResponse> getMyJobs(String email);

    JobResponse getJob(String email, Long id);

    JobResponse updateJob(String email, Long id, UpdateJobRequest request);

    void deleteJob(String email, Long id);

    /**
     * Confirms the user applied to this personal job -- ANY personal job, regardless of
     * source (manual, Greenhouse import, a saved catalog posting, ...). Scoped to jobs
     * this user owns.
     *
     * <p>Idempotent: a repeat confirmation while already {@code applied=true} never
     * fails, never duplicates a row, and never resets {@code appliedAt} -- only the
     * actual {@code false -> true} transition (a fresh application, including one after
     * a revoke) gets a fresh {@code appliedAt}. This guarantee lives in {@code
     * JobMapper#markApplied}'s own {@code UPDATE} (a {@code COALESCE} against the row's
     * current value), not in a check-then-write here -- see that method's javadoc for
     * why. {@code JobCatalogService#markApplied} calls the exact same mapper method for
     * the exact same reason, so both entry points share one semantic, not two.
     *
     * <p>Distinct from {@code JobCatalogService#markApplied} only in what it's keyed
     * by: that one takes a catalog provider's opaque {@code catalogJobId} and
     * saves-if-not-already-saved as part of the same call (used from the search
     * results, before a personal job id exists yet); this one is for a job ALREADY in
     * the user's own list.
     */
    JobResponse markApplied(String email, Long id);

    /** Reverses a mistaken confirmation. Scoped to jobs this user owns, same as {@link #markApplied}. */
    JobResponse clearApplied(String email, Long id);

    BulkDeleteJobsResponse bulkDeleteJobs(String email, List<Long> jobIds);

    JobMatchResponse matchJob(String email, Long jobId, Long resumeId);

    TailoredResumeResponse tailorResume(
            String email,
            Long jobId,
            Long resumeId,
            TailorMode mode
    );

    TailoredResumeResponse createTailorProposal(
            String email,
            Long jobId,
            Long resumeId
    );

    TailoredResumeResponse createTailorProposal(
            String email,
            Long jobId,
            Long resumeId,
            String productMode
    );

    V3OpportunityMap scanEnhancedOpportunity(
            String email,
            Long jobId,
            Long resumeId,
            List<String> skippedQuestionIds
    );

    AnswerEnhancedQuestionResponse answerEnhancedQuestion(
            String email,
            Long jobId,
            Long resumeId,
            AnswerEnhancedQuestionRequest request
    );

    TailoredResumeResponse getTailorProposal(
            String email,
            Long jobId,
            Long proposalId
    );

    TailoredResumeResponse confirmTailorProposal(
            String email,
            Long jobId,
            Long proposalId,
            List<String> selectedNodeIds
    );

    TailoredResumeResponse confirmTailorProposal(
            String email,
            Long jobId,
            Long proposalId,
            List<String> selectedNodeIds,
            List<EditedTailorChange> editedChanges
    );

    TailoredResumeResponse confirmTailorProposal(
            String email,
            Long jobId,
            Long proposalId,
            ConfirmTailorProposalRequest request
    );

    TailoredResumeResponse continueTailorProposal(
            String email,
            Long jobId,
            Long proposalId
    );

    Path downloadTailoredResume(
            String email,
            Long jobId,
            String fileName
    );

    ImportJobsResponse importGreenhouseJobs(String email, String boardToken);

    JobSearchResponse searchJobs(String email, JobSearchRequest request);

    JobSearchResponse loadNextSearchPage(String email, UUID searchSessionId, int expectedPage);

    ImportSearchResultsResponse importSearchResults(String email, ImportSearchResultsRequest request);

    SaveJobSearchResultResponse saveSearchResult(String email, SaveJobSearchResultRequest request);
}
