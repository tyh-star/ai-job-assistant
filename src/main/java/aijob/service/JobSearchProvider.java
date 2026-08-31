package aijob.service;

import aijob.dto.JobSearchRequest;
import aijob.jobsearch.JobSearchCapability;
import aijob.jobsearch.ProviderSearchPage;

public interface JobSearchProvider {

    String providerName();

    JobSearchCapability capability();

    ProviderSearchPage search(JobSearchRequest request);

    default ProviderSearchPage search(JobSearchRequest request, String cursor) {
        return search(request);
    }
}
