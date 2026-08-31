package aijob.jobsearch;

import aijob.dto.JobSearchRequest;

import java.util.List;
import java.util.UUID;

public interface SearchSessionStore {

    SearchSession create(
            long userId,
            String provider,
            JobSearchCapability capability,
            JobSearchRequest request,
            List<ProviderJobHit> hits);

    SearchSession create(
            long userId,
            String provider,
            JobSearchCapability capability,
            JobSearchRequest request,
            List<ProviderJobHit> hits,
            String nextCursor);

    SearchSession requireForUser(UUID sessionId, long userId);

    SearchSession replace(SearchSession session);
}
