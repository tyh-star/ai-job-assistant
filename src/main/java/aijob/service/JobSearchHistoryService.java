package aijob.service;

import aijob.dto.JobSearchHistoryItemResponse;
import aijob.dto.RecordJobSearchHistoryRequest;

import java.util.List;

public interface JobSearchHistoryService {

    /** Upsert-or-no-op. Empty criteria write nothing. Never throws for empty input. */
    void record(String email, RecordJobSearchHistoryRequest request);

    List<JobSearchHistoryItemResponse> list(String email);

    void deleteOne(String email, Long id);

    void deleteAll(String email);
}
