package aijob.jobsearch;

import aijob.dto.JobSearchRequest;
import aijob.exception.JobSearchSessionExpiredException;
import aijob.exception.JobSearchSessionNotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class CaffeineSearchSessionStore implements SearchSessionStore {

    static final Duration USABLE_TTL = Duration.ofMinutes(15);
    static final Duration RETENTION = Duration.ofHours(2);
    static final int MAXIMUM_SIZE = 2_000;

    private final Cache<UUID, SearchSession> sessions;
    private final Clock clock;

    public CaffeineSearchSessionStore(@org.springframework.beans.factory.annotation.Qualifier("jobSearchClock") Clock clock) {
        this.clock = clock;
        this.sessions = Caffeine.newBuilder()
                .expireAfterWrite(RETENTION)
                .maximumSize(MAXIMUM_SIZE)
                .build();
    }

    @Override
    public SearchSession create(
            long userId,
            String provider,
            JobSearchCapability capability,
            JobSearchRequest request,
            List<ProviderJobHit> hits) {
        return create(userId, provider, capability, request, hits, null);
    }

    @Override
    public SearchSession create(
            long userId,
            String provider,
            JobSearchCapability capability,
            JobSearchRequest request,
            List<ProviderJobHit> hits,
            String nextCursor) {
        Instant expiresAt = clock.instant().plus(USABLE_TTL);
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                userId,
                provider,
                capability,
                request,
                expiresAt,
                hits,
                nextCursor);
        sessions.put(session.id(), session);
        return session;
    }

    @Override
    public SearchSession requireForUser(UUID sessionId, long userId) {
        if (sessionId == null) {
            throw new JobSearchSessionNotFoundException("Search session was not found");
        }
        SearchSession session = sessions.getIfPresent(sessionId);
        if (session == null || session.userId() != userId) {
            throw new JobSearchSessionNotFoundException("Search session was not found");
        }
        if (clock.instant().isAfter(session.expiresAt())) {
            throw new JobSearchSessionExpiredException("Search session has expired");
        }
        return session;
    }

    @Override
    public SearchSession replace(SearchSession session) {
        if (session == null || session.id() == null) {
            throw new JobSearchSessionNotFoundException("Search session was not found");
        }
        SearchSession existing = sessions.getIfPresent(session.id());
        if (existing == null) {
            throw new JobSearchSessionNotFoundException("Search session was not found");
        }
        SearchSession stored = new SearchSession(
                existing.id(),
                existing.userId(),
                session.provider(),
                session.capability(),
                session.request(),
                existing.expiresAt(),
                session.hits(),
                session.nextCursor(),
                session.currentPage(),
                session.pages(),
                session.seenRawIds(),
                session.seenCanonicalUrls(),
                session.seenFingerprints(),
                session.loadedCursorHashes());
        sessions.put(stored.id(), stored);
        return stored;
    }
}
