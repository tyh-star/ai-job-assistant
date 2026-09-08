package aijob.service.impl;

import aijob.config.JobCatalogProviderProperty;
import aijob.dto.CitySuggestion;
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
import aijob.jobsearch.ExternalJobIds;
import aijob.mapper.JobMapper;
import aijob.mapper.UserMapper;
import aijob.service.JobCatalogService;
import aijob.util.ExternalLinks;
import aijob.util.HtmlSanitizer;
import aijob.util.HtmlTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Clock;
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
 * {@code jobs.external_job_id = ExternalJobIds.persistId(SOURCE, public_slug)} — a
 * SHA-256 hex digest, NEVER the raw slug. {@code jobs.external_job_id} is
 * {@code VARCHAR(64)} and a real FreeHire slug (title + company + a random suffix) can
 * genuinely exceed that (confirmed live: a 79-character real slug, rejected before this
 * fix). {@link JobService#saveSearchResult JSearch} already faced the exact same
 * problem — an upstream id with no length guarantee — and already solved it the same
 * way: hash it to a fixed-width digest rather than truncating it (truncating would let
 * two different long slugs collide on their shared 64-character prefix and be treated
 * as the same job). The RAW slug is still what is used for everything that talks to
 * FreeHire itself or forms a public reference: {@link #FREEHIRE_ID_PREFIX}-prefixed
 * catalog ids, {@code GET /jobs/{slug}}, and the apply link. Only the DATABASE ROW's
 * identity uses the hash — see {@link #persistId}.
 *
 * <p><b>Rolling upgrade.</b> A row saved before {@link #persistId} existed stored the
 * raw slug directly — nothing wrong with it at the time, a short slug fit
 * {@code VARCHAR(64)} fine. Every lookup goes through {@link
 * #findExistingByPersistedOrLegacyId} (a single row) or {@link #savedByExternalId} (a
 * page of search results), both of which recognize EITHER form, so an old row is
 * treated as the same posting rather than getting a second, hashed row inserted next
 * to it. Old rows are read as-is, on purpose — nothing here rewrites or deletes one.
 */
@Service
@ConditionalOnProperty(
        name = JobCatalogProviderProperty.PROPERTY,
        havingValue = JobCatalogProviderProperty.FREEHIRE,
        matchIfMissing = true)
public class FreeHireJobCatalogService implements JobCatalogService {

    public static final String SOURCE = "FREEHIRE";
    static final String FREEHIRE_ID_PREFIX = "freehire:";
    /**
     * A sanity bound on the RAW slug as an HTTP path segment -- not a storage
     * constraint (see {@link #persistId}, which maps any length down to a fixed
     * 64-character hash before it ever reaches {@code jobs.external_job_id}). Generous
     * on purpose: real FreeHire slugs run well under this; this exists only to reject
     * something that could never plausibly be a real slug (an abuse/mistake guard),
     * never to reject a real one.
     */
    static final int MAX_CATALOG_SLUG_HTTP_PATH_LENGTH = 2000;

    private static final Logger log = LoggerFactory.getLogger(FreeHireJobCatalogService.class);

    private final FreeHireClient freeHireClient;
    private final JobMapper jobMapper;
    private final UserMapper userMapper;
    private final Clock clock;

    public FreeHireJobCatalogService(
            FreeHireClient freeHireClient,
            JobMapper jobMapper,
            UserMapper userMapper,
            @Qualifier("jobSearchClock") Clock clock) {
        this.freeHireClient = freeHireClient;
        this.jobMapper = jobMapper;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    @Override
    public JobCatalogSearchResponse search(String email, JobCatalogSearchRequest request) {
        Long userId = requireUserIdOrNullForAnonymous(email);
        JobCatalogSearchRequest safe = request == null
                ? new JobCatalogSearchRequest(null, null, null, null, null, null, null, null, null, null, null)
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
        // Purely for the cityMatch label below -- NEVER read by buildSearchParams
        // (already built above) and never sent to FreeHire. See
        // JobCatalogSearchRequest#cityCountry's javadoc.
        String matchedCity = blankToNull(safe.city());
        String matchedCityCountry = countryOrNull(safe.cityCountry());
        List<JobCatalogSummary> results = toSummaries(userId, hits, matchedCity, matchedCityCountry);

        long total = response.metaOrEmpty().total();
        int totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        boolean hasMore = (long) page * size < total;
        return new JobCatalogSearchResponse(page, size, total, totalPages, hasMore, results);
    }

    @Override
    public JobCatalogDetail findDetail(String email, String catalogJobId) {
        Long userId = requireUserIdOrNullForAnonymous(email);
        String slug = requireSlug(catalogJobId);

        FreeHireJobJson hit = freeHireClient.getBySlug(slug);
        Job saved = userId == null
                ? null
                : findExistingByPersistedOrLegacyId(userId, slug);

        return toDetail(hit, saved);
    }

    @Override
    @Transactional
    public SaveCatalogJobResponse saveToMyJobs(String email, String catalogJobId) {
        User user = requireUser(email);
        String slug = requireSlug(catalogJobId);
        String idRef = FREEHIRE_ID_PREFIX + slug;

        // Idempotency check first: a repeat save must not re-fetch-and-compare, it must
        // simply return the row that already exists. Checked by the PERSISTED id first
        // (see #persistId), falling back to the RAW slug -- a rolling-upgrade
        // compatibility read for a row this backend saved BEFORE that fix existed,
        // when it stored the raw slug directly. Never re-insert a hashed row on top of
        // one of those -- see #findExistingByPersistedOrLegacyId's javadoc.
        Job existing = findExistingByPersistedOrLegacyId(user.getId(), slug);
        if (existing != null) {
            return new SaveCatalogJobResponse(existing.getId(), idRef, true, toJobResponse(existing));
        }

        // Re-fetched from FreeHire rather than trusting anything the client already had
        // cached from an earlier search response: the snapshot that gets tailored against
        // must be what FreeHire says the posting actually is right now, not whatever the
        // browser happened to be holding. Still the RAW slug here -- FreeHire itself has
        // no idea what this backend persists a row under.
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
        // The PERSISTED id, never the raw slug -- see #persistId's javadoc for why
        // storing the raw slug directly (as this line used to) is what let a real
        // 79-character slug overflow jobs.external_job_id VARCHAR(64) and fail before
        // ever reaching FreeHire.
        job.setExternalJobId(persistId(slug));
        jobMapper.insert(job);

        Job persisted = jobMapper.findById(job.getId());
        Job result = persisted != null ? persisted : job;
        return new SaveCatalogJobResponse(result.getId(), idRef, false, toJobResponse(result));
    }

    @Override
    @Transactional
    public SaveCatalogJobResponse markApplied(String email, String catalogJobId) {
        // Reuses the idempotent save path -- applied/appliedAt live on the same personal
        // jobs row a save creates, and this backend never marks a job applied without a
        // row to attach that fact to. saveToMyJobs already re-validates the user and the
        // slug, and is idempotent on its own, so a repeat confirmation costs one extra
        // UPDATE, never a duplicate row. That UPDATE (jobMapper#markApplied) is called
        // unconditionally, exactly like JobService#markApplied does for the Jobs page's
        // own tabs -- both entry points share the exact same idempotency guarantee
        // (a repeat confirmation never resets appliedAt, only a genuine false->true
        // transition does) because they both resolve to the same SQL statement, whose
        // own COALESCE is what actually enforces it. See that method's javadoc.
        SaveCatalogJobResponse saveResult = saveToMyJobs(email, catalogJobId);
        Long userId = requireUser(email).getId();
        LocalDateTime appliedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jobMapper.markApplied(saveResult.jobId(), userId, appliedAt);
        Job updated = jobMapper.findById(saveResult.jobId());
        return new SaveCatalogJobResponse(
                saveResult.jobId(), saveResult.catalogJobId(), saveResult.alreadySaved(), toJobResponse(updated));
    }

    @Override
    @Transactional
    public SaveCatalogJobResponse clearApplied(String email, String catalogJobId) {
        User user = requireUser(email);
        String slug = requireSlug(catalogJobId);
        Job existing = findExistingByPersistedOrLegacyId(user.getId(), slug);
        if (existing == null) {
            // Nothing was ever saved, so nothing was ever marked applied either -- from
            // the caller's perspective there is no applied state here to revoke.
            throw new JobNotFoundException("Catalog job not found");
        }
        jobMapper.clearApplied(existing.getId(), user.getId());
        Job updated = jobMapper.findById(existing.getId());
        return new SaveCatalogJobResponse(
                existing.getId(), FREEHIRE_ID_PREFIX + slug, true, toJobResponse(updated));
    }

    // ---------------------------------------------------------------------------
    // request -> FreeHire query params
    // ---------------------------------------------------------------------------

    /**
     * Translates the catalog search request into FreeHire's own query vocabulary.
     * {@code countries}/{@code cities} confirmed live against {@code GET
     * /agent/jobs/search} on 2026-09-03; {@code cities} additionally cross-checked
     * against the pinned upstream source ({@code internal/search/search/query_filter.go})
     * on 2026-09-05, which is also where the fact below comes from that a hand-rolled
     * request could not have discovered any other way.
     *
     * <p><b>{@code countries} and {@code cities} OR together, they do not narrow.</b>
     * The upstream source's own comment on {@code locationFacets} says it plainly:
     * "selecting the 'Global' region and 'Brazil' widens the results (Global OR Brazil)
     * instead of intersecting them". {@code countries}, {@code regions} and {@code
     * cities} are ONE shared OR-group, not independent AND'able facets. Sending both
     * {@code countries=ca} and {@code cities=Toronto} in the same request does NOT mean
     * "Toronto, Canada" -- it means "anywhere in Canada, OR anywhere named Toronto",
     * which silently widens a search the caller believed they were narrowing. So: a
     * request may set {@code city} OR {@code country}, never both -- see {@link
     * #requireNoConflictingLocationFacets}, which rejects the combination outright
     * rather than silently picking one.
     *
     * <p><b>{@code cities} does NOT imply a specific country -- it is a bare name
     * match, nothing more.</b> Confirmed by reading the pinned upstream source (not
     * guessed): a job document's {@code Cities} field ({@code internal/job/job/job.go})
     * is a flat {@code []string} with no per-element country pairing, and the
     * Meilisearch filter for {@code cities=X} ({@code query_filter.go}'s {@code Eq()})
     * is a plain equality match against that array -- it never joins back to the same
     * document's own {@code countries} value at query time. A search for {@code
     * cities=London} will therefore match EVERY posting whose {@code cities} array
     * contains the string "London", regardless of which country that posting is
     * actually in -- a UK posting and a Canadian posting both named "London" are
     * indistinguishable by this facet alone. (Within a single document, {@code
     * jobderive.Derive()} does derive {@code cities}/{@code countries}/{@code regions}
     * together from one parsed location, so one job's own fields are internally
     * consistent -- but that says nothing about what OTHER documents with the same
     * city name carry, which is what a search across many postings actually returns.)
     * Sending {@code countries} alongside {@code cities} would not fix this -- since
     * they share the OR-group above, it would only widen the results further, never
     * narrow them to the intended country. There is no request shape that expresses
     * "this exact city, in this exact country" against this provider today; callers
     * needing a guaranteed country scope must use the {@code country} facet alone.
     *
     * <p>Deliberately not translated, and why:
     * <ul>
     *   <li>{@code location} (free text) has no FreeHire facet equivalent and,
     *       critically, no RELIABLE automatic mapping onto one -- "Toronto" cannot be
     *       safely turned into a country/region/city code without a geocoding step. An
     *       earlier version of this method folded {@code location} into {@code q}
     *       instead, which silently turned a location filter into a full-text search: a
     *       posting whose JD merely MENTIONS a place in its body text could match even
     *       though the posting's actual location is somewhere else entirely. That is
     *       exactly the failure mode {@link #requireNoUnsupportedLocationText} exists to
     *       prevent: free-text {@code location} is rejected with a validation error
     *       rather than silently reinterpreted. The structured {@code city} facet (see
     *       {@link JobCatalogService#searchCities}, backed by FreeHire's own
     *       {@code GET /geo/cities}) is the real, working replacement -- the frontend no
     *       longer offers a free-text box that reaches this rejection in normal use.</li>
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
        String city = blankToNull(request.city());
        String country = countryOrNull(request.country());
        requireNoConflictingLocationFacets(city, country);

        String q = blankToNull(request.q());
        if (q != null) {
            params.add("q", q);
        }
        if (city != null) {
            // See buildSearchParams' javadoc: cities/countries share one OR-group, so
            // countries is deliberately NOT also sent here -- cities is a name-only
            // match with no country correlation, and adding countries alongside it
            // would only widen the results further, never narrow them to one country.
            params.add("cities", city);
        } else if (country != null) {
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
                            + "use city or country to filter by location");
        }
    }

    /**
     * See {@link #buildSearchParams}'s javadoc for why {@code city} and {@code country}
     * cannot both be sent to FreeHire in the same request: they share one OR-group with
     * {@code regions}, so combining them only widens the results further -- it never
     * narrows to "this city, in this country", because {@code cities} does not carry
     * any country information of its own. Rejected outright rather than silently
     * preferring one -- the same "never silently drop a selected filter" rule {@link
     * #requireNoUnsupportedLocationText} already follows.
     */
    private static void requireNoConflictingLocationFacets(String city, String country) {
        if (city != null && country != null) {
            throw new InvalidJobSearchException(
                    "city and country cannot both be set in the same search; "
                            + "combining them would widen the results rather than narrow them");
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

    private List<JobCatalogSummary> toSummaries(
            Long userId, List<FreeHireJobJson> hits, String matchedCity, String matchedCityCountry) {
        if (hits.isEmpty()) {
            return List.of();
        }
        List<String> slugs = hits.stream().map(FreeHireJobJson::publicSlug).filter(s -> s != null).toList();
        // userId == null means an anonymous (unauthenticated) search -- never looked up
        // against anyone's saved/applied state, so every result below comes back
        // saved=false/applied=false rather than leaking one user's data to another
        // caller who happens to search for the same posting.
        Map<String, JobMapper.ExternalIdAndPersonalJobId> saved =
                userId == null ? Map.of() : savedByExternalId(userId, slugs);

        List<JobCatalogSummary> summaries = new ArrayList<>(hits.size());
        for (FreeHireJobJson hit : hits) {
            JobMapper.ExternalIdAndPersonalJobId row = saved.get(hit.publicSlug());
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
                    sourceLabel(hit),
                    parseInstant(hit.postedAt()),
                    parseInstant(hit.createdAt()),
                    parseInstant(hit.createdAt()),
                    hit.closedAt() != null ? "EXPIRED" : "ACTIVE",
                    row != null,
                    row != null ? row.id() : null,
                    row != null && row.applied(),
                    row != null ? row.appliedAt() : null,
                    cityMatchOrNull(hit, matchedCity, matchedCityCountry)));
        }
        return summaries;
    }

    /**
     * See {@link JobCatalogSummary.CityMatch}'s javadoc. {@code null} whenever this
     * search was not city-scoped at all, OR (defensively -- this should never happen
     * given FreeHire's own {@code cities} filter already ran server-side, but this
     * class never assumes a provider response without checking) this specific hit's
     * own {@code cities} does not actually contain the requested value.
     */
    private static JobCatalogSummary.CityMatch cityMatchOrNull(
            FreeHireJobJson hit, String matchedCity, String matchedCityCountry) {
        if (matchedCity == null) {
            return null;
        }
        List<String> cities = hit.cities();
        if (cities == null || !cities.contains(matchedCity)) {
            log.warn("FreeHire returned a hit whose own cities does not contain the requested "
                    + "city -- integration is out of sync with the API: slug={} requestedCity={} cities={}",
                    hit.publicSlug(), matchedCity, cities);
            return null;
        }
        boolean countryConfirmed = matchedCityCountry != null
                && hit.countries() != null
                && hit.countries().contains(matchedCityCountry.toLowerCase(Locale.ROOT));
        return new JobCatalogSummary.CityMatch(matchedCity, matchedCityCountry, countryConfirmed);
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
                saved != null ? saved.getId() : null,
                saved != null && saved.isApplied(),
                saved != null ? saved.getAppliedAt() : null);
    }

    /**
     * Keyed by the RAW slug (what every caller here actually has on hand — a
     * {@code FreeHireJobJson#publicSlug()}), even though the underlying query has to
     * search by whatever {@code jobs.external_job_id} actually stores for that slug —
     * the PERSISTED id (see {@link #persistId}) for a row saved after the identity fix,
     * OR the raw slug itself for a row saved BEFORE it (rolling-upgrade compatibility —
     * see {@link #findExistingByPersistedOrLegacyId}'s javadoc). Both forms are
     * searched for in the SAME query — never two round trips per page of results — and
     * the reverse-mapping built here (both the persisted id AND the raw slug point back
     * to that same raw slug) attaches whichever form the row actually stored back to
     * the right search result.
     */
    private Map<String, JobMapper.ExternalIdAndPersonalJobId> savedByExternalId(Long userId, List<String> slugs) {
        if (slugs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> idToSlug = new HashMap<>();
        List<String> lookupIds = new ArrayList<>(slugs.size() * 2);
        for (String slug : slugs) {
            String persisted = persistId(slug);
            lookupIds.add(persisted);
            idToSlug.put(persisted, slug);
            // Legacy compatibility: a row saved before the identity fix stored the raw
            // slug verbatim. Searched for alongside the persisted id in this same
            // batch, never as a separate query.
            lookupIds.add(slug);
            idToSlug.putIfAbsent(slug, slug);
        }
        Map<String, JobMapper.ExternalIdAndPersonalJobId> bySlug = new HashMap<>();
        for (JobMapper.ExternalIdAndPersonalJobId row
                : jobMapper.findSavedExternalJobIds(userId, SOURCE, lookupIds)) {
            String slug = idToSlug.get(row.externalJobId());
            if (slug != null) {
                bySlug.put(slug, row);
            }
        }
        return bySlug;
    }

    /**
     * The identity {@code jobs.external_job_id} stores for a row THIS backend saves
     * from now on — a SHA-256 hex digest of the raw slug, never the raw slug itself.
     * See the class javadoc's "Provenance" paragraph for why: a real FreeHire slug can
     * exceed the column's {@code VARCHAR(64)}, and truncating instead of hashing would
     * let two different long slugs collide on a shared prefix. {@link
     * ExternalJobIds#persistId} is the exact same helper {@code JobServiceImpl} already
     * uses for JSearch's own unbounded-length raw ids — this reuses it rather than
     * inventing a second scheme.
     *
     * <p>Every NEW write of {@code jobs.external_job_id} for this provider MUST go
     * through this method — never the raw slug directly. Reads must go through {@link
     * #findExistingByPersistedOrLegacyId} instead, which also recognizes a row saved
     * before this method existed.
     */
    private static String persistId(String slug) {
        return ExternalJobIds.persistId(SOURCE, slug);
    }

    /**
     * Rolling-upgrade compatibility for a single lookup (the batch equivalent is
     * {@link #savedByExternalId}). A row saved before {@link #persistId} existed
     * stored the RAW slug verbatim in {@code jobs.external_job_id} — a short slug fit
     * {@code VARCHAR(64)} just fine at the time, so nothing about that old row is
     * wrong, it simply predates the identity this backend now writes for NEW saves.
     *
     * <p>Tries the persisted (hashed) id first — what every save from now on writes,
     * so this is the common case for anything saved after the fix. Only falls back to
     * the raw slug if that misses, so an old row is found and treated as the SAME
     * posting: {@code saveToMyJobs} returns {@code alreadySaved=true} against it rather
     * than inserting a second, hashed row for a posting already saved once under the
     * old identity — and {@code markApplied}/{@code clearApplied} (which both resolve
     * to this same lookup) operate on that same old row directly.
     *
     * <p>Deliberately does NOT rewrite or delete the old row — this method only reads.
     * A slug that happens to itself look like a 64-character lowercase hex string
     * would theoretically be ambiguous with a persisted id, but no real FreeHire slug
     * is shaped that way (they are human-readable kebab-case), so this is not treated
     * as a practical concern.
     */
    private Job findExistingByPersistedOrLegacyId(Long userId, String slug) {
        Job byPersistedId = jobMapper.findByUserIdAndSourceAndExternalJobId(userId, SOURCE, persistId(slug));
        if (byPersistedId != null) {
            return byPersistedId;
        }
        return jobMapper.findByUserIdAndSourceAndExternalJobId(userId, SOURCE, slug);
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
                job.isApplied(),
                job.getAppliedAt(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }

    /**
     * Strips the {@code freehire:} namespace prefix, refusing a reference that does not
     * carry it (a {@code local_catalog} reference sent while this provider is active --
     * reported as not-found, since from the caller's perspective that is exactly what it
     * is: this provider has no such row) or is blank after it.
     *
     * <p>Deliberately NOT a length check against {@code jobs.external_job_id}'s storage
     * width any more -- see {@link #persistId} for why a slug of any real length is
     * handled by hashing, never by rejecting it here. What IS still validated is that
     * this could plausibly be a real slug at all, as a 400 (malformed input), never
     * confused with the two things it is NOT:
     * <ul>
     *   <li>a 404 (this site has no such row, or the provider prefix is wrong) --
     *       {@link JobNotFoundException}</li>
     *   <li>the upstream posting's own detail genuinely being gone --
     *       {@link aijob.exception.JobDetailUnavailableException}, thrown only after a
     *       real {@code GET /jobs/{slug}} call, which a malformed slug must never even
     *       reach</li>
     * </ul>
     */
    private static String requireSlug(String catalogJobId) {
        if (catalogJobId == null || !catalogJobId.startsWith(FREEHIRE_ID_PREFIX)) {
            throw new JobNotFoundException("Catalog job not found");
        }
        String slug = catalogJobId.substring(FREEHIRE_ID_PREFIX.length());
        if (slug.isBlank()) {
            throw new JobNotFoundException("Catalog job not found");
        }
        if (!slug.equals(slug.strip())) {
            throw new InvalidJobSearchException("catalogJobId must not have leading or trailing whitespace");
        }
        if (slug.length() > MAX_CATALOG_SLUG_HTTP_PATH_LENGTH) {
            throw new InvalidJobSearchException("catalogJobId is too long to be a real FreeHire slug");
        }
        // No path separators or traversal sequences -- RestClient's own URI builder
        // already percent-encodes this slug when it builds the outbound request (see
        // FreeHireClient#getBySlug), so a "/" could never actually escape this path
        // segment there; this rejects it up front anyway, as a 400, rather than relying
        // solely on that downstream encoding to keep a malformed value harmless.
        if (slug.contains("/") || slug.contains("\\") || slug.contains("..")) {
            throw new InvalidJobSearchException(
                    "catalogJobId must not contain path separators or path-traversal sequences");
        }
        return slug;
    }

    @Override
    public List<CitySuggestion> searchCities(String q, String country) {
        String query = blankToNull(q);
        if (query == null) {
            // Matches FreeHire's own GET /geo/cities?q= behaviour: a blank query is an
            // empty list, not a call worth making.
            return List.of();
        }
        String countryCode = country == null ? null : countryOrNull(country);
        return freeHireClient.searchCities(query, countryCode == null ? null : countryCode.toLowerCase(Locale.ROOT))
                .stream()
                .map(city -> new CitySuggestion(city.value(), city.country()))
                .toList();
    }

    private User requireUser(String email) {
        User user = userMapper.findByEmail(email);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        return user;
    }

    /**
     * {@code null} in ({@code null} out: an anonymous caller (no JWT at all) gets an
     * anonymous, entirely public result -- no user lookup, no exception. A non-null
     * email that does not resolve to a real user is still an error (a stale/forged
     * token), never silently treated as anonymous.
     */
    private Long requireUserIdOrNullForAnonymous(String email) {
        return email == null ? null : requireUser(email).getId();
    }
}
