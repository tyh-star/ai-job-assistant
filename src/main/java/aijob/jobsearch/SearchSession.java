package aijob.jobsearch;

import aijob.dto.JobSearchRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public record SearchSession(
        UUID id,
        long userId,
        String provider,
        JobSearchCapability capability,
        JobSearchRequest request,
        Instant expiresAt,
        List<ProviderJobHit> hits,
        String nextCursor,
        int currentPage,
        List<List<ProviderJobHit>> pages,
        Set<String> seenRawIds,
        Set<String> seenCanonicalUrls,
        Set<String> seenFingerprints,
        Set<String> loadedCursorHashes
) {
    public SearchSession {
        hits = hits == null ? List.of() : List.copyOf(hits);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor.strip();
        currentPage = currentPage < 1 ? 1 : currentPage;
        pages = copyPages(pages, hits);
        JobSearchDeduper.SeenSets seen = seenSets(seenRawIds, seenCanonicalUrls, seenFingerprints, hits);
        seenRawIds = Set.copyOf(seen.rawIds());
        seenCanonicalUrls = Set.copyOf(seen.canonicalUrls());
        seenFingerprints = Set.copyOf(seen.fingerprints());
        loadedCursorHashes = loadedCursorHashes == null ? Set.of() : Set.copyOf(loadedCursorHashes);
    }

    public SearchSession(
            UUID id,
            long userId,
            String provider,
            JobSearchCapability capability,
            JobSearchRequest request,
            Instant expiresAt,
            List<ProviderJobHit> hits) {
        this(id, userId, provider, capability, request, expiresAt, hits, null, 1, null, null, null, null, null);
    }

    public SearchSession(
            UUID id,
            long userId,
            String provider,
            JobSearchCapability capability,
            JobSearchRequest request,
            Instant expiresAt,
            List<ProviderJobHit> hits,
            String nextCursor) {
        this(id, userId, provider, capability, request, expiresAt, hits, nextCursor, 1, null, null, null, null, null);
    }

    public Map<String, ProviderJobHit> hitsByRawId() {
        return hits.stream()
                .filter(hit -> hit.rawExternalJobId() != null)
                .collect(Collectors.toUnmodifiableMap(
                        ProviderJobHit::rawExternalJobId,
                        Function.identity(),
                        (left, right) -> left));
    }

    public List<ProviderJobHit> pageHits(int page) {
        if (page < 1 || page > pages.size()) {
            return List.of();
        }
        return pages.get(page - 1);
    }

    public boolean hasLoadedPage(int page) {
        return page >= 1 && page <= pages.size();
    }

    public SearchSession withNextPage(List<ProviderJobHit> uniqueHits, String newCursor, String usedCursorHash) {
        List<ProviderJobHit> mergedHits = new ArrayList<>(hits);
        if (uniqueHits != null) {
            mergedHits.addAll(uniqueHits);
        }
        List<List<ProviderJobHit>> mergedPages = new ArrayList<>(pages);
        mergedPages.add(uniqueHits == null ? List.of() : List.copyOf(uniqueHits));
        Set<String> rawIds = new HashSet<>(seenRawIds);
        Set<String> urls = new HashSet<>(seenCanonicalUrls);
        Set<String> fingerprints = new HashSet<>(seenFingerprints);
        if (uniqueHits != null) {
            for (ProviderJobHit hit : uniqueHits) {
                JobSearchDeduper.remember(hit, rawIds, urls, fingerprints);
            }
        }
        Set<String> cursorHashes = new HashSet<>(loadedCursorHashes);
        if (usedCursorHash != null && !usedCursorHash.isBlank()) {
            cursorHashes.add(usedCursorHash);
        }
        return new SearchSession(
                id,
                userId,
                provider,
                capability,
                request,
                expiresAt,
                mergedHits,
                newCursor,
                currentPage + 1,
                mergedPages,
                rawIds,
                urls,
                fingerprints,
                cursorHashes);
    }

    private static List<List<ProviderJobHit>> copyPages(List<List<ProviderJobHit>> pages, List<ProviderJobHit> hits) {
        if (pages == null || pages.isEmpty()) {
            return List.of(hits == null ? List.of() : List.copyOf(hits));
        }
        List<List<ProviderJobHit>> copied = new ArrayList<>(pages.size());
        for (List<ProviderJobHit> page : pages) {
            copied.add(page == null ? List.of() : List.copyOf(page));
        }
        return List.copyOf(copied);
    }

    private static JobSearchDeduper.SeenSets seenSets(
            Set<String> seenRawIds,
            Set<String> seenCanonicalUrls,
            Set<String> seenFingerprints,
            List<ProviderJobHit> hits) {
        if (seenRawIds != null && seenCanonicalUrls != null && seenFingerprints != null) {
            return new JobSearchDeduper.SeenSets(seenRawIds, seenCanonicalUrls, seenFingerprints);
        }
        return JobSearchDeduper.seenFrom(hits);
    }
}
