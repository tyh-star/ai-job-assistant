package aijob.jobsearch;

import aijob.util.JobFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JobSearchDeduper {

    private JobSearchDeduper() {
    }

    public static String canonicalApplyUrl(String sourceUrl) {
        String normalized = JobFingerprint.normalizeUrl(sourceUrl);
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) {
            normalized = normalized.substring(0, fragment);
        }
        return normalized;
    }

    public static String fingerprint(ProviderJobHit hit) {
        if (hit == null) {
            return "|||";
        }
        return JobFingerprint.normalize(hit.company())
                + "|" + JobFingerprint.normalize(hit.title())
                + "|" + JobFingerprint.normalize(hit.location())
                + "|" + JobFingerprint.normalize(hit.postedAt());
    }

    public static boolean isDuplicate(
            ProviderJobHit hit,
            Set<String> seenRawIds,
            Set<String> seenCanonicalUrls,
            Set<String> seenFingerprints) {
        if (hit == null) {
            return true;
        }
        if (hit.rawExternalJobId() != null && seenRawIds.contains(hit.rawExternalJobId())) {
            return true;
        }
        String url = canonicalApplyUrl(hit.sourceUrl());
        if (!url.isBlank() && seenCanonicalUrls.contains(url)) {
            return true;
        }
        return seenFingerprints.contains(fingerprint(hit));
    }

    public static void remember(
            ProviderJobHit hit,
            Set<String> seenRawIds,
            Set<String> seenCanonicalUrls,
            Set<String> seenFingerprints) {
        if (hit == null) {
            return;
        }
        if (hit.rawExternalJobId() != null) {
            seenRawIds.add(hit.rawExternalJobId());
        }
        String url = canonicalApplyUrl(hit.sourceUrl());
        if (!url.isBlank()) {
            seenCanonicalUrls.add(url);
        }
        seenFingerprints.add(fingerprint(hit));
    }

    public static UniquePage uniquePage(
            List<ProviderJobHit> incoming,
            Set<String> seenRawIds,
            Set<String> seenCanonicalUrls,
            Set<String> seenFingerprints) {
        List<ProviderJobHit> unique = new ArrayList<>();
        if (incoming == null) {
            return new UniquePage(unique, seenRawIds, seenCanonicalUrls, seenFingerprints);
        }
        for (ProviderJobHit hit : incoming) {
            if (isDuplicate(hit, seenRawIds, seenCanonicalUrls, seenFingerprints)) {
                continue;
            }
            unique.add(hit);
            remember(hit, seenRawIds, seenCanonicalUrls, seenFingerprints);
        }
        return new UniquePage(unique, seenRawIds, seenCanonicalUrls, seenFingerprints);
    }

    public static SeenSets seenFrom(List<ProviderJobHit> hits) {
        Set<String> rawIds = new LinkedHashSet<>();
        Set<String> urls = new LinkedHashSet<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        if (hits != null) {
            for (ProviderJobHit hit : hits) {
                remember(hit, rawIds, urls, fingerprints);
            }
        }
        return new SeenSets(rawIds, urls, fingerprints);
    }

    public record UniquePage(
            List<ProviderJobHit> hits,
            Set<String> seenRawIds,
            Set<String> seenCanonicalUrls,
            Set<String> seenFingerprints
    ) {
    }

    public record SeenSets(
            Set<String> rawIds,
            Set<String> canonicalUrls,
            Set<String> fingerprints
    ) {
    }
}
