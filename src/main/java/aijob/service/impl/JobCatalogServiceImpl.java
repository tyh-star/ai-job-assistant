package aijob.service.impl;

import aijob.catalog.CatalogJobSourceRow;
import aijob.catalog.JobCatalogSearchQuery;
import aijob.catalog.SqlDialect;
import aijob.dto.CitySuggestion;
import aijob.dto.JobCatalogDetail;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.JobCatalogSummary;
import aijob.dto.JobResponse;
import aijob.dto.SaveCatalogJobResponse;
import aijob.entity.Job;
import aijob.entity.JobCatalogEntry;
import aijob.entity.User;
import aijob.entity.UserSavedCatalogJob;
import aijob.enums.CatalogJobStatus;
import aijob.enums.EmploymentType;
import aijob.enums.RemoteType;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.JobCatalogMapper;
import aijob.mapper.JobMapper;
import aijob.mapper.UserMapper;
import aijob.mapper.UserSavedCatalogJobMapper;
import aijob.service.JobCatalogService;
import aijob.util.HtmlSanitizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads this deployment's own {@code job_catalog} table, built by the self-hosted
 * collector. Explicit opt-in only — {@code aijob.job-catalog.provider=local_catalog} —
 * so switching back from FreeHire never happens by a missing property falling through
 * to whichever bean the classpath happens to offer.
 */
@Service
@ConditionalOnProperty(
        name = aijob.config.JobCatalogProviderProperty.PROPERTY,
        havingValue = aijob.config.JobCatalogProviderProperty.LOCAL_CATALOG)
public class JobCatalogServiceImpl implements JobCatalogService {

    /** Marks personal jobs created from the catalog, and keys the idempotent save. */
    public static final String CATALOG_SOURCE = "JOB_CATALOG";

    private final JobCatalogMapper jobCatalogMapper;
    private final UserSavedCatalogJobMapper userSavedCatalogJobMapper;
    private final JobMapper jobMapper;
    private final UserMapper userMapper;
    private final SqlDialect sqlDialect;
    private final Clock clock;

    public JobCatalogServiceImpl(JobCatalogMapper jobCatalogMapper,
                                 UserSavedCatalogJobMapper userSavedCatalogJobMapper,
                                 JobMapper jobMapper,
                                 UserMapper userMapper,
                                 SqlDialect sqlDialect,
                                 @Qualifier("jobSearchClock") Clock clock) {
        this.jobCatalogMapper = jobCatalogMapper;
        this.userSavedCatalogJobMapper = userSavedCatalogJobMapper;
        this.jobMapper = jobMapper;
        this.userMapper = userMapper;
        this.sqlDialect = sqlDialect;
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

        JobCatalogSearchQuery query = new JobCatalogSearchQuery();
        query.setStatuses(List.of(CatalogJobStatus.ACTIVE.name()));
        query.setUseFullText(sqlDialect.supportsFullText());
        applyKeyword(query, safe.q());
        query.setLocation(likeOrNull(safe.location()));
        query.setCountryCode(countryOrNull(safe.country()));
        query.setRemoteType(remoteTypeOrNull(safe.remoteType()));
        query.setEmploymentType(employmentTypeOrNull(safe.employmentType()));
        query.setPostedAfter(postedAfterOrNull(safe.postedWithinDays()));
        query.setSort(sortOrDefault(safe.sort(), query.getKeyword() != null));
        query.setLimit(size);
        query.setOffset((page - 1) * size);

        long total = jobCatalogMapper.countSearch(query);
        List<JobCatalogEntry> entries = total == 0 ? List.of() : jobCatalogMapper.search(query);

        List<JobCatalogSummary> results = toSummaries(userId, entries);
        int totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        boolean hasMore = (long) page * size < total;
        return new JobCatalogSearchResponse(page, size, total, totalPages, hasMore, results);
    }

    @Override
    public JobCatalogDetail findDetail(String email, String catalogJobId) {
        Long userId = requireUserIdOrNullForAnonymous(email);
        JobCatalogEntry entry = requireCatalogEntry(parseCatalogJobId(catalogJobId));

        UserSavedCatalogJob mapping = userId == null
                ? null
                : userSavedCatalogJobMapper.findByUserAndCatalogJob(userId, entry.getId());
        Job personal = mapping == null ? null : jobMapper.findById(mapping.getPersonalJobId());
        String source = sourceLabels(List.of(entry.getId())).get(entry.getId());

        return new JobCatalogDetail(
                String.valueOf(entry.getId()),
                entry.getTitle(),
                entry.getCompanyName(),
                entry.getDescriptionText(),
                // Sanitized again on the way out so a row written by any other path
                // can still never deliver script to the browser.
                HtmlSanitizer.sanitize(entry.getDescriptionHtml()),
                entry.getLocationText(),
                entry.getCity(),
                entry.getRegion(),
                entry.getCountryCode(),
                entry.getRemoteType(),
                entry.getEmploymentType(),
                entry.getSalaryMin(),
                entry.getSalaryMax(),
                entry.getCurrency(),
                entry.getCanonicalUrl(),
                entry.getApplyUrl(),
                source,
                entry.getPostedAt(),
                entry.getFirstSeenAt(),
                entry.getLastSeenAt(),
                entry.getStatus(),
                mapping != null,
                mapping == null ? null : mapping.getPersonalJobId(),
                personal != null && personal.isApplied(),
                personal == null ? null : personal.getAppliedAt());
    }

    @Override
    @Transactional
    public SaveCatalogJobResponse saveToMyJobs(String email, String catalogJobId) {
        User user = requireUser(email);
        JobCatalogEntry entry = requireCatalogEntry(parseCatalogJobId(catalogJobId));
        if (CatalogJobStatus.REMOVED.name().equals(entry.getStatus())) {
            throw new JobNotFoundException("Catalog job is no longer available");
        }

        String externalJobId = String.valueOf(entry.getId());
        String idRef = String.valueOf(entry.getId());

        UserSavedCatalogJob mapping =
                userSavedCatalogJobMapper.findByUserAndCatalogJob(user.getId(), entry.getId());
        if (mapping != null) {
            Job existing = jobMapper.findById(mapping.getPersonalJobId());
            if (existing != null && user.getId().equals(existing.getUserId())) {
                return new SaveCatalogJobResponse(existing.getId(), idRef, true, toJobResponse(existing));
            }
        }

        // The unique key on (user_id, source, external_job_id) can already hold a row
        // if a previous save committed the job but its mapping was later removed.
        Job existing = jobMapper.findByUserIdAndSourceAndExternalJobId(
                user.getId(), CATALOG_SOURCE, externalJobId);
        if (existing != null) {
            if (mapping == null) {
                insertMapping(user.getId(), entry.getId(), existing.getId());
            }
            return new SaveCatalogJobResponse(existing.getId(), idRef, true, toJobResponse(existing));
        }

        // A snapshot: later catalog edits must not rewrite what the user tailors against.
        Job job = new Job();
        job.setUserId(user.getId());
        job.setCompany(entry.getCompanyName());
        job.setTitle(entry.getTitle());
        job.setLocation(entry.getLocationText());
        job.setJobUrl(entry.getApplyUrl() != null ? entry.getApplyUrl() : entry.getCanonicalUrl());
        job.setDescription(descriptionForPersonalJob(entry));
        job.setSource(CATALOG_SOURCE);
        job.setStatus("SAVED");
        job.setExternalJobId(externalJobId);
        jobMapper.insert(job);

        insertMapping(user.getId(), entry.getId(), job.getId());

        Job saved = jobMapper.findById(job.getId());
        Job result = saved != null ? saved : job;
        return new SaveCatalogJobResponse(result.getId(), idRef, false, toJobResponse(result));
    }

    @Override
    @Transactional
    public SaveCatalogJobResponse markApplied(String email, String catalogJobId) {
        // Reuses the idempotent save path -- applied/appliedAt live on the same personal
        // jobs row a save creates, and this backend never marks a job applied without a
        // row to attach that fact to.
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
        Long parsedId = parseCatalogJobId(catalogJobId);
        String externalJobId = String.valueOf(parsedId);
        Job existing = jobMapper.findByUserIdAndSourceAndExternalJobId(user.getId(), CATALOG_SOURCE, externalJobId);
        if (existing == null) {
            // Nothing was ever saved, so nothing was ever marked applied either.
            throw new JobNotFoundException("Catalog job not found");
        }
        jobMapper.clearApplied(existing.getId(), user.getId());
        Job updated = jobMapper.findById(existing.getId());
        return new SaveCatalogJobResponse(existing.getId(), externalJobId, true, toJobResponse(updated));
    }

    /**
     * The wire id is opaque to the caller but must actually be a numeric row id in
     * this provider. A non-numeric value here means the client sent a reference from
     * the OTHER provider (e.g. a {@code freehire:...} id after switching back to
     * local_catalog) -- reported as not-found rather than a parse error, since from
     * the caller's side it is exactly that: this provider has no such row.
     */
    private static Long parseCatalogJobId(String catalogJobId) {
        if (catalogJobId == null) {
            throw new JobNotFoundException("Catalog job not found");
        }
        try {
            return Long.valueOf(catalogJobId);
        } catch (NumberFormatException ex) {
            throw new JobNotFoundException("Catalog job not found");
        }
    }

    private void insertMapping(Long userId, Long catalogJobId, Long personalJobId) {
        UserSavedCatalogJob mapping = new UserSavedCatalogJob();
        mapping.setUserId(userId);
        mapping.setCatalogJobId(catalogJobId);
        mapping.setPersonalJobId(personalJobId);
        userSavedCatalogJobMapper.insert(mapping);
    }

    private List<JobCatalogSummary> toSummaries(Long userId, List<JobCatalogEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(entries.size());
        for (JobCatalogEntry entry : entries) {
            ids.add(entry.getId());
        }
        Map<Long, String> sources = sourceLabels(ids);

        // userId == null means an anonymous (unauthenticated) search -- never looked up
        // against anyone's saved/applied state, so every result below comes back
        // saved=false/applied=false rather than leaking one user's data to another
        // caller who happens to search for the same posting.
        Set<Long> savedIds = userId == null
                ? Set.of()
                : new HashSet<>(userSavedCatalogJobMapper.findSavedCatalogJobIds(userId, ids));
        Map<Long, Long> personalJobIds = new HashMap<>();
        for (Long id : savedIds) {
            UserSavedCatalogJob mapping = userSavedCatalogJobMapper.findByUserAndCatalogJob(userId, id);
            if (mapping != null) {
                personalJobIds.put(id, mapping.getPersonalJobId());
            }
        }
        // One batch fetch instead of one applied-status query per saved row.
        Map<Long, Job> personalJobsById = personalJobIds.isEmpty()
                ? Map.of()
                : indexById(jobMapper.findByIds(new ArrayList<>(personalJobIds.values())));

        List<JobCatalogSummary> summaries = new ArrayList<>(entries.size());
        for (JobCatalogEntry entry : entries) {
            Long personalJobId = personalJobIds.get(entry.getId());
            Job personal = personalJobId == null ? null : personalJobsById.get(personalJobId);
            summaries.add(new JobCatalogSummary(
                    String.valueOf(entry.getId()),
                    entry.getTitle(),
                    entry.getCompanyName(),
                    entry.getLocationText(),
                    entry.getCountryCode(),
                    entry.getRemoteType(),
                    entry.getEmploymentType(),
                    entry.getSalaryMin(),
                    entry.getSalaryMax(),
                    entry.getCurrency(),
                    entry.getCanonicalUrl(),
                    sources.get(entry.getId()),
                    entry.getPostedAt(),
                    entry.getFirstSeenAt(),
                    entry.getLastSeenAt(),
                    entry.getStatus(),
                    savedIds.contains(entry.getId()),
                    personalJobId,
                    personal != null && personal.isApplied(),
                    personal == null ? null : personal.getAppliedAt(),
                    // local_catalog's own `location` is a genuine free-text LIKE filter
                    // (see this class's `search`), not FreeHire's ambiguous `cities`
                    // facet -- there is no "matched vs. posting's own location" gap to
                    // surface here, so cityMatch is always absent for this provider.
                    null));
        }
        return summaries;
    }

    private static Map<Long, Job> indexById(List<Job> jobs) {
        Map<Long, Job> byId = new HashMap<>();
        for (Job job : jobs) {
            byId.put(job.getId(), job);
        }
        return byId;
    }

    private Map<Long, String> sourceLabels(List<Long> catalogJobIds) {
        if (catalogJobIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> labels = new HashMap<>();
        for (CatalogJobSourceRow row : jobCatalogMapper.findSourcesForCatalogJobs(catalogJobIds)) {
            labels.putIfAbsent(row.getCatalogJobId(), row.getProviderType());
        }
        return labels;
    }

    private void applyKeyword(JobCatalogSearchQuery query, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String keyword = raw.strip();
        query.setKeyword(keyword);
        query.setKeywordLike(likeOrNull(keyword));

        // FULLTEXT BOOLEAN MODE treats + - * " ( ) ~ < > @ as operators; user input is
        // reduced to plain terms so a stray character cannot become a syntax error.
        StringBuilder booleanQuery = new StringBuilder();
        for (String token : keyword.split("\\s+")) {
            String cleaned = token.replaceAll("[^\\p{L}\\p{N}_]", "");
            if (cleaned.isEmpty()) {
                continue;
            }
            booleanQuery.append('+').append(cleaned).append('*').append(' ');
        }
        String booleanForm = booleanQuery.toString().strip();
        query.setKeywordBoolean(booleanForm.isEmpty() ? null : booleanForm);
        if (query.isUseFullText() && query.getKeywordBoolean() == null) {
            // Nothing indexable survived; fall back to LIKE rather than dropping the filter.
            query.setUseFullText(false);
        }
    }

    private static String likeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String escaped = value.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static String countryOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String code = value.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{2}")) {
            throw new InvalidJobSearchException("country must be a 2-letter country code");
        }
        return code;
    }

    private static String remoteTypeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RemoteType.valueOf(value.strip().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new InvalidJobSearchException("remoteType must be REMOTE, HYBRID, ONSITE or UNSPECIFIED");
        }
    }

    private static String employmentTypeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return EmploymentType.valueOf(value.strip().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new InvalidJobSearchException("employmentType is not a supported value");
        }
    }

    private LocalDateTime postedAfterOrNull(Integer postedWithinDays) {
        if (postedWithinDays == null) {
            return null;
        }
        if (!JobCatalogSearchRequest.ALLOWED_POSTED_WITHIN_DAYS.contains(postedWithinDays)) {
            throw new InvalidJobSearchException("postedWithinDays must be 1, 3, 7, 14, or 30");
        }
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(postedWithinDays);
    }

    private static String sortOrDefault(String value, boolean hasKeyword) {
        if (value == null || value.isBlank()) {
            return hasKeyword
                    ? JobCatalogSearchRequest.SORT_RELEVANCE
                    : JobCatalogSearchRequest.SORT_POSTED_AT;
        }
        String sort = value.strip().toUpperCase(Locale.ROOT);
        if (!JobCatalogSearchRequest.SORT_RELEVANCE.equals(sort)
                && !JobCatalogSearchRequest.SORT_POSTED_AT.equals(sort)) {
            throw new InvalidJobSearchException("sort must be RELEVANCE or POSTED_AT");
        }
        return sort;
    }

    private static String descriptionForPersonalJob(JobCatalogEntry entry) {
        String text = entry.getDescriptionText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        return entry.getTitle() + " — " + entry.getCompanyName();
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

    private JobCatalogEntry requireCatalogEntry(Long catalogJobId) {
        if (catalogJobId == null) {
            throw new JobNotFoundException("Catalog job not found");
        }
        JobCatalogEntry entry = jobCatalogMapper.findById(catalogJobId);
        if (entry == null) {
            throw new JobNotFoundException("Catalog job not found");
        }
        return entry;
    }

    @Override
    public List<CitySuggestion> searchCities(String q, String country) {
        // No structured city dictionary of its own -- this provider's location facet is
        // the free-text `location` LIKE filter (see #search), a separate mechanism.
        // Empty, not an error: "no suggestions available for this provider" is a
        // legitimate, expected answer, not a failure.
        return List.of();
    }

    private User requireUser(String email) {
        User user = userMapper.findByEmail(email);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        return user;
    }

    /**
     * {@code null} in, {@code null} out: an anonymous caller (no JWT at all) gets an
     * anonymous, entirely public result -- no user lookup, no exception. A non-null
     * email that does not resolve to a real user is still an error (a stale/forged
     * token), never silently treated as anonymous.
     */
    private Long requireUserIdOrNullForAnonymous(String email) {
        return email == null ? null : requireUser(email).getId();
    }
}
