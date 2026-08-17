package aijob.service;

import aijob.dto.BulkDeleteJobsResponse;
import aijob.dto.CreateJobRequest;
import aijob.dto.ImportJobsResponse;
import aijob.dto.JobMatchResponse;
import aijob.dto.JobResponse;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.UpdateJobRequest;
import aijob.enums.TailorMode;

import java.nio.file.Path;
import java.util.List;

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

    Path downloadTailoredResume(
            String email,
            Long jobId,
            String fileName
    );

    ImportJobsResponse importGreenhouseJobs(String email, String boardToken);
}
