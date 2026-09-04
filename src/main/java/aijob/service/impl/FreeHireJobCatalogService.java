package aijob.service.impl;

import aijob.config.JobCatalogProviderProperty;
import aijob.dto.JobCatalogDetail;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.JobCatalogSummary;
import aijob.dto.JobResponse;
import aijob.dto.SaveCatalogJobResponse;
import aijob.entity.Job;
import aijob.entity.User;
import aijob.enums.EmploymentType;
import aijob.enums.RemoteType;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.freehire.FreeHireClient;
import aijob.freehire.FreeHireJobJson;
import aijob.freehire.FreeHireSearchResponseJson;
import aijob.mapper.JobMapper;
import aijob.mapper.UserMapper;
import aijob.service.JobCatalogService;
import aijob.util.ExternalLinks;
import aijob.util.HtmlSanitizer;
import aijob.util.HtmlTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the site's job search from FreeHire's own hosted API (freehire.me/api/v1)
 * instead of this deployment's local collector. Every search, detail read, and the
 * fetch a save performs go to FreeHire; nothing FreeHire returns is written to
 * {@code job_catalog} or any other local table on its own — only an explicit
 * {@link #saveToMyJobs} copies the ONE posting a user chose into their own {@code jobs}
 * row, exactly like every other job source this deployment already saves that way.
 *
 * <p><b>Identity.</b> A wire-level catalog reference from this provider is
 * {@code "freehire:" + public_slug} — {@link #FREEHIRE_ID_PREFIX} — never the bare
 * numeric id {@code local_catalog} uses, so the two can never collide and a reference
 * from one provider is never mistaken for the other's row after a config switch. The
 * slug itself is treated as an opaque string throughout: never parsed as a number,
 * never reused as a local database id.
 *
 * <p><b>Provenance for a saved job.</b> {@code jobs.source = "FREEHIRE"},
 * {@code jobs.external_job_id = <public_slug>} (unprefixed — the {@code source} column
 * already scopes it, per the same {@code (user_id, source, external_job_id)} unique key
 * every other provider's saves already rely on for idempotency).
 */
@Service
@ConditionalOnProperty(
        name = JobCatalogProviderProperty.PROPERTY,
        havingValue = JobCatalogProviderProperty.FREEHIRE,
        matchIfMissing = true)
public class FreeHireJobCatalogService implements JobCatalogService {

    public static final String SOURCE = "FREEHIRE";
    static final String FREEHIRE_ID_PREFIX = "freehire:";
    /** jobs.external_job_id is VARCHAR(64); a slug over this is refused, not truncated. */
    static final int MAX_SLUG_LENGTH = 64;

    private static final Logger log = LoggerFactory.getLogger(FreeHireJobCatalogService.class);

    private final FreeHireClient freeHireClient;
    private final JobMapper jobMapper;
    private final UserMapper userMapper;

    public FreeHireJobCatalogService(FreeHireClient freeHireClient, JobMapper jobMapper, UserMapper userMapper) {
        this.freeHireClient = freeHireClient;
        this.jobMapper = jobMapper;
        this.userMapper = userMapper;
    }

    @Override
    public JobCatalogSearchResponse search(String email, JobCatalogSearchRequest request) {
        User user = requireUser(email);
        JobCatalogSearchRequest safe = request == null
                ? new JobCatalogSearchRequest(null, null, null, null, null, null, null, null, null)
                : request;

        int page = safe.page() == null ? 1 : safe.page();
        if (page < 1) {
            throw new InvalidJobSearchException("page must be at least 1");
        }
        int size = safe.size() == null ? JobCatalogSearchRequest.DEFAULT_PAGE_SIZE : safe.size();
        if (size < 1) {
            throw new InvalidJobSearchException("size must be at least 1");
        }
        size = Math.min(size, JobCatalogSearchRequest.MAX_PAGE_SIZE);
        int offset = (page - 1) * size;

        MultiValueMap<String, String> params = buildSearchParams(safe, size, offset);
        FreeHireSearchResponseJson response = freeHireClient.search(params);

        // meta.ignored_params is FreeHire telling us a param WE sent was not read as a
        // filter. Every param this method sends is one we built ourselves from a fixed,
        // validated vocabulary, so seeing one back here means our translation has drifted
        // from FreeHire's actual contract -- worth a loud warning, never silence, and
        // never presented to the caller as "the filter worked".
        List<String> ignored = response.metaOrEmpty().ignoredParamsOrEmpty().stream()
                .map(p -> p.param() + (p.didYouMean() != null ? " (did_you_mean=" + p.didYouMean() + ")" : ""))
                .toList();
        if (!ignored.isEmpty()) {
            log.warn("FreeHire ignored params we sent ourselves -- integration is out of sync with the API: {}",
                    ignored);
        }

        List<FreeHireJobJson> hits = response.dataOrEmpty();
        List<JobCatalogSummary> results = toSummaries(user.getId(), hits);

        long total = response.metaOrEmpty().total();
        int totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        boolean hasMore = (long) page * size < total;
        return new JobCatalogSearchResponse(page, size, total, totalPages, hasMore, results);
    }

    @Override
    public JobCatalogDetail findDetail(String email, String catalogJobId) {
        User user = requireUser(email);
        String slug = requireSlug(catalogJobId);

        FreeHireJobJson hit = freeHireClient.getBySlug(slug);
        Job saved = jobMapper.findByUserIdAndSourceAndExternalJobId(user.getId(), SOURCE, slug);

        return toDetail(hit, saved);
    }

    @Override
    @Transactional
    public SaveCatalogJobResponse saveToMyJobs(String email, String catalogJobId) {
        User user = requireUser(email);
        String slug = requireSlug(catalogJobId);
        String idRef = FREEHIRE_ID_PREFIX + slug;

        // Idempotency check first: a repeat save must not re-fetch-and-compare, it must
        // simply return the row that already exists.
        Job existing = jobMapper.findByUserIdAndSourceAndExternalJobId(user.getId(), SOURCE, slug);
        if (existing != null) {
            return new SaveCatalogJobResponse(existing.getId(), idRef, true, toJobResponse(existing));
        }

        // Re-fetched from FreeHire rather than trusting anything the client already had
        // cached from an earlier search response: the snapshot that gets tailored against
        // must be what FreeHire says the posting actually is right now, not whatever the
        // browser happened to be holding.
        FreeHireJobJson hit = freeHireClient.getBySlug(slug);

        Job job = new Job();
        job.setUserId(user.getId());
        job.setCompany(blankToPlaceholder(hit.company(), "Unknown company"));
        job.setTitle(blankToPlaceholder(hit.title(), "Untitled position"));
        job.setLocation(hit.location());
        String link = ExternalLinks.displayUrl(hit.url());
        job.setJobUrl(link);
        job.setDescription(descriptionForPersonalJob(hit));
        job.setSource(SOURCE);
        job.setStatus("SAVED");
        job.setExternalJobId(slug);
        jobMapper.insert(job);

        Job persisted = jobMapper.findById(job.getId());
        Job result = persisted != null ? persisted : job;
        return new SaveCatalogJobResponse(result.getId(), idRef, false, toJobResponse(result));
    }

    // ---------------------------------------------------------------------------
    // request -> FreeHire query params
    // ---------------------------------------------------------------------------

    /**
     * Translates the catalog search request into FreeHire's own query vocabulary,
     * confirmed live against {@code GET /agent/jobs/search} on 2026-09-03.
     *
     * <p>Deliberately not translated, and why:
     * <ul>
     *   <li>{@code location} (free text) has no FreeHire facet equivalent (their
     *       geography facets are {@code countries}/{@code regions}/{@code cities}, all
     *       controlled vocabularies, not free text) and, critically, no RELIABLE
     *       automatic mapping onto one -- "Toronto" cannot be safely turned into a
     *       country/region/city code without a geocoding step this integration does not
     *       have. An earlier version of this method folded {@code location} into
     *       {@code q} instead, which silently turned a location filter into a full-text
     *       search: a posting whose JD merely MENTIONS a place in its body text (e.g. a
     *       relocation note, a client's HQ, an unrelated office) could match even though
     *       the posting's actual location is somewhere else entirely -- a false positive
     *       masquerading as a working filter. That is exactly the failure mode {@link
     *       #requireNoUnsupportedLocationText} exists to prevent: free-text
     *       {@code location} is now rejected with a clear validation error rather than
     *       silently reinterpreted, so a caller finds out immediately that this filter
     *       is not supported here rather than getting confidently wrong results. Filter
     *       by {@code country} (a real, structured FreeHire facet) instead.</li>
     *   <li>{@code employmentType} of {@code TEMPORARY} or {@code OTHER} has no
     *       FreeHire value ({@code full_time}/{@code part_time}/{@code contract}/
     *       {@code internship}/{@code fellowship} is their whole vocabulary, confirmed
     *       from the upstream source's {@code vocab.EmploymentTypeValues}). Sending an
     *       unsupported value would filter against a value the index never carries and
     *       return zero results while looking like a successful filter; silently
     *       omitting the facet would make the same selection look honoured while running
     *       fully unfiltered. Neither is acceptable, so {@link #employmentTypeOrNull}
     *       rejects these two values outright with a validation error -- the caller's
     *       selection is never quietly dropped either way.</li>
     * </ul>
     */
    static MultiValueMap<String, String> buildSearchParams(
            JobCatalogSearchRequest request, int limit, int offset) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();

        requireNoUnsupportedLocationText(request.location());
        String q = blankToNull(request.q());
        if (q != null) {
            params.add("q", q);
        }
        String country = countryOrNull(request.country());
        if (country != null) {
            params.add("countries", country.toLowerCase(Locale.ROOT));
        }
        String workMode = workModeOrNull(request.remoteType());
        if (workMode != null) {
            params.add("work_mode", workMode);
        }
        String employmentType = employmentTypeOrNull(request.employmentType());
        if (employmentType != null) {
            params.add("employment_type", employmentType);
        }
        Integer postedWithinDays = postedWithinDaysOrNull(request.postedWithinDays());
        if (postedWithinDays != null) {
            params.add("posted_within_days", String.valueOf(postedWithinDays));
        }
        String sort = sortParamOrNull(request.sort());
        if (sort != null) {
            params.add("sort", sort);
            params.add("order", "desc");
        }
        params.add("limit", String.valueOf(limit));
        params.add("offset", String.valueOf(offset));
        return params;
    }

    /**
     * {@code local_catalog}'s own {@code location} facet is a genuine {@code LIKE}
     * filter over that provider's data and stays meaningful there (see
     * {@code JobCatalogServiceImpl}) -- this rejection is specific to THIS provider,
     * where free text cannot be safely turned into any of FreeHire's real geography
     * facets. Rejected rather than silently dropped or silently reinterpreted as a
     * text search: see {@link #buildSearchParams}'s javadoc for why folding it into
     * {@code q} was wrong.
     */
    private static void requireNoUnsupportedLocationText(String location) {
        if (blankToNull(location) != null) {
            throw new InvalidJobSearchException(
                    "location is not supported as a text filter by the current job search provider; "
                            + "use country to filter by location");
        }
    }

    private static String countryOrNull(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        String code = v.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{2}")) {
            throw new InvalidJobSearchException("country must be a 2-letter country code");
        }
        return code;
    }

    private static String workModeOrNull(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        RemoteType type;
        try {
            type = RemoteType.valueOf(v.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidJobSearchException("remoteType must be REMOTE, HYBRID, ONSITE or UNSPECIFIED");
        }
        return switch (type) {
            case REMOTE -> "remote";
            case HYBRID -> "hybrid";
            case ONSITE -> "onsite";
            case UNSPECIFIED -> null;
        };
    }

    private static String employmentTypeOrNull(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        EmploymentType type;
        try {
            type = EmploymentType.valueOf(v.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidJobSearchException("employmentType is not a supported value");
        }
        return switch (type) {
            case FULL_TIME -> "full_time";
            case PART_TIME -> "part_time";
            case CONTRACT -> "contract";
            case INTERNSHIP -> "internship";
            case UNSPECIFIED -> null;
            // No FreeHire equivalent -- see buildSearchParams' javadoc. Rejected outright
            // rather than silently omitted: omitting would make the caller's selection
            // look honoured while the search actually ran unfiltered by employment type.
            // The frontend does not offer these two values while FreeHire is the active
            // provider (see JobCatalogSearch.tsx), so a real user should never trigger
            // this in normal use; it exists to fail loudly for any OTHER caller of this
            // API (an older cached frontend bundle, a direct API call, a future client)
            // rather than let their selection vanish unnoticed.
            case TEMPORARY, OTHER -> throw new InvalidJobSearchException(
                    "employmentType " + type + " is not supported by the current job search provider");
        };
    }

    private static Integer postedWithinDaysOrNull(Integer value) {
        if (value == null) {
            return null;
        }
        if (!JobCatalogSearchRequest.ALLOWED_POSTED_WITHIN_DAYS.contains(value)) {
            throw new InvalidJobSearchException("postedWithinDays must be 1, 3, 7, 14, or 30");
        }
        return value;
    }

    private static String sortParamOrNull(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        String sort = v.strip().toUpperCase(Locale.ROOT);
        if (JobCatalogSearchRequest.SORT_POSTED_AT.equals(sort)) {
            return "posted_at";
        }
        if (JobCatalogSearchRequest.SORT_RELEVANCE.equals(sort)) {
            // No sort param: FreeHire ranks by text relevance when a query is present,
            // and falls back to posted_at desc itself when it is not -- the same
            // "relevance where meaningful, freshest otherwise" default local_catalog
            // already applies, so this needs no query-side branch on whether q is set.
            return null;
        }
        throw new InvalidJobSearchException("sort must be RELEVANCE or POSTED_AT");
    }

    // ---------------------------------------------------------------------------
    // FreeHire response -> catalog DTOs
    // ---------------------------------------------------------------------------

    private List<JobCatalogSummary> toSummaries(Long userId, List<FreeHireJobJson> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }
        List<String> slugs = hits.stream().map(FreeHireJobJson::publicSlug).filter(s -> s != null).toList();
        Map<String, Long> savedPersonalJobIds = savedPersonalJobIds(userId, slugs);

        List<JobCatalogSummary> summaries = new ArrayList<>(hits.size());
        for (FreeHireJobJson hit : hits) {
            Long personalJobId = savedPersonalJobIds.get(hit.publicSlug());
            summaries.add(new JobCatalogSummary(
                    FREEHIRE_ID_PREFIX + hit.publicSlug(),
                    hit.title(),
                    hit.company(),
                    hit.location(),
                    countryCodeOrNull(hit.countries()),
                    RemoteType.fromNullable(hit.workMode()).name(),
                    // See buildSearchParams' javadoc: FreeHire does not expose a flat
                    // employment-type field on the job object itself (only inside the
                    // nested `enrichment`, which this integration does not parse), so
                    // display is UNSPECIFIED even though the FILTER above still works --
                    // filtering and displaying read from different parts of FreeHire's
                    // response.
                    EmploymentType.UNSPECIFIED.name(),
                    null,
                    null,
                    null,
                    ExternalLinks.displayUrl(hit.url()),
                    ExternalLinks.displayUrl(hit.url()),
                    sourceLabel(hit),
                    parseInstant(hit.postedAt()),
                    parseInstant(hit.createdAt()),
                    parseInstant(hit.createdAt()),
                    hit.closedAt() != null ? "EXPIRED" : "ACTIVE",
                    snippet(HtmlTextExtractor.toPlainText(hit.description())),
                    personalJobId != null,
                    personalJobId));
        }
        return summaries;
    }

    private JobCatalogDetail toDetail(FreeHireJobJson hit, Job saved) {
        String text = HtmlTextExtractor.toPlainText(hit.description());
        return new JobCatalogDetail(
                FREEHIRE_ID_PREFIX + hit.publicSlug(),
                hit.title(),
                hit.company(),
                text,
                HtmlSanitizer.sanitize(hit.description()),
                hit.location(),
                firstOrNull(hit.cities()),
                firstOrNull(hit.regions()),
                countryCodeOrNull(hit.countries()),
                RemoteType.fromNullable(hit.workMode()).name(),
                EmploymentType.UNSPECIFIED.name(),
                null,
                null,
                null,
                ExternalLinks.displayUrl(hit.url()),
                ExternalLinks.displayUrl(hit.url()),
                sourceLabel(hit),
                parseInstant(hit.postedAt()),
                parseInstant(hit.createdAt()),
                parseInstant(hit.createdAt()),
                hit.closedAt() != null ? "EXPIRED" : "ACTIVE",
                saved != null,
                saved != null ? saved.getId() : null);
    }

    private Map<String, Long> savedPersonalJobIds(Long userId, List<String> slugs) {
        if (slugs.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> byExternalId = new HashMap<>();
        for (JobMapper.ExternalIdAndPersonalJobId row
                : jobMapper.findSavedExternalJobIds(userId, SOURCE, slugs)) {
            byExternalId.put(row.externalJobId(), row.id());
        }
        return byExternalId;
    }

    private static String sourceLabel(FreeHireJobJson hit) {
        String underlying = blankToNull(hit.source());
        return underlying == null ? "FREEHIRE" : underlying.toUpperCase(Locale.ROOT);
    }

    private static String descriptionForPersonalJob(FreeHireJobJson hit) {
        String text = HtmlTextExtractor.toPlainText(hit.description());
        if (text != null && !text.isBlank()) {
            return text;
        }
        return blankToPlaceholder(hit.title(), "Untitled position")
                + " — " + blankToPlaceholder(hit.company(), "Unknown company");
    }

    private static String snippet(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flattened = text.strip().replaceAll("\\s+", " ");
        int max = 280;
        if (flattened.length() <= max) {
            return flattened;
        }
        return flattened.substring(0, max).strip() + "…";
    }

    /**
     * The first country FreeHire reported, uppercased to match the display convention
     * {@code local_catalog} already uses (and its own {@code countryOrNull} validator
     * enforces on input) -- confirmed live 2026-09-03 that FreeHire itself reports
     * lowercase ({@code ["co","us"]}); this is purely a display normalization on our
     * side, not a reinterpretation of what FreeHire said.
     */
    private static String countryCodeOrNull(List<String> countries) {
        String first = firstOrNull(countries);
        return first == null ? null : first.toUpperCase(Locale.ROOT);
    }

    private static String firstOrNull(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String first = values.get(0);
        return first == null || first.isBlank() ? null : first;
    }

    private static LocalDateTime parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(iso.strip()), ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToPlaceholder(String value, String placeholder) {
        String v = blankToNull(value);
        return v == null ? placeholder : v;
    }

    private static JobResponse toJobResponse(Job job) {
        return new JobResponse(
                job.getId(),
                job.getCompany(),
                job.getTitle(),
                job.getLocation(),
                job.getJobUrl(),
                job.getDescription(),
                job.getSource(),
                job.getStatus(),
                job.getExternalJobId(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }

    /**
     * Strips the {@code freehire:} namespace prefix, refusing a reference that does not
     * carry it (a {@code local_catalog} reference sent while this provider is active --
     * reported as not-found, since from the caller's perspective that is exactly what it
     * is: this provider has no such row) and a slug too long to ever have been stored in
     * {@code jobs.external_job_id} (VARCHAR(64)).
     */
    private static String requireSlug(String catalogJobId) {
        if (catalogJobId == null || !catalogJobId.startsWith(FREEHIRE_ID_PREFIX)) {
            throw new JobNotFoundException("Catalog job not found");
        }
        String slug = catalogJobId.substring(FREEHIRE_ID_PREFIX.length());
        if (slug.isBlank() || slug.length() > MAX_SLUG_LENGTH) {
            throw new JobNotFoundException("Catalog job not found");
        }
        return slug;
    }

    private User requireUser(String email) {
        User user = userMapper.findByEmail(email);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        return user;
    }
}
