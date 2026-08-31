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
