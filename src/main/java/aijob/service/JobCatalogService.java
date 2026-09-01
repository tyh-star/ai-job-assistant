package aijob.service;

import aijob.dto.JobCatalogDetail;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.SaveCatalogJobResponse;

/**
 * Search over the self-hosted catalog of collected public postings.
 * Reads only the local database; it never calls an external job API.
 */
public interface JobCatalogService {

    JobCatalogSearchResponse search(String email, JobCatalogSearchRequest request);

    JobCatalogDetail findDetail(String email, Long catalogJobId);

    /**
     * Copies a catalog posting into the caller's own {@code jobs} table and records
     * the mapping. Repeating the call for the same user and posting returns the
     * existing personal job instead of creating another one.
     */
    SaveCatalogJobResponse saveToMyJobs(String email, Long catalogJobId);
}
