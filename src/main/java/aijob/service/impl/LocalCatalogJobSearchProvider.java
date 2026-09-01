package aijob.service.impl;

import aijob.catalog.JobCatalogSearchQuery;
import aijob.catalog.SqlDialect;
import aijob.dto.JobSearchRequest;
import aijob.entity.JobCatalogEntry;
import aijob.enums.CatalogJobStatus;
import aijob.jobsearch.JobSearchCapability;
import aijob.jobsearch.ProviderJobHit;
import aijob.jobsearch.ProviderSearchPage;
import aijob.mapper.JobCatalogMapper;
import aijob.service.JobSearchProvider;
import aijob.service.JobSearchProviderRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Serves the legacy {@code /api/v1/jobs/search} route from the local catalog.
 *
 * <p>Registering this alongside the new {@code /api/v1/job-catalog} routes means both
 * search paths read the same local table, so switching
 * {@code JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG} removes every outbound job-API
 * call rather than only the one the new page happens to use. A database failure
 * propagates as an error; it is never answered by falling back to a remote provider.
 */
@Component
public class LocalCatalogJobSearchProvider implements JobSearchProvider {

    public static final String SOURCE = JobSearchProviderRegistry.LOCAL_CATALOG;
    private static final DateTimeFormatter POSTED_AT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JobCatalogMapper jobCatalogMapper;
    private final SqlDialect sqlDialect;
    private final Clock clock;

    public LocalCatalogJobSearchProvider(JobCatalogMapper jobCatalogMapper,
                                         SqlDialect sqlDialect,
                                         @Qualifier("jobSearchClock") Clock clock) {
        this.jobCatalogMapper = jobCatalogMapper;
        this.sqlDialect = sqlDialect;
        this.clock = clock;
    }

    @Override
    public String providerName() {
        return SOURCE;
    }

    @Override
    public JobSearchCapability capability() {
        return JobSearchCapability.FULL_DESCRIPTION;
    }

    @Override
    public ProviderSearchPage search(JobSearchRequest request) {
        return searchAtOffset(request, 0);
    }

    @Override
    public ProviderSearchPage search(JobSearchRequest request, String cursor) {
        return searchAtOffset(request, parseOffset(cursor));
    }

    private ProviderSearchPage searchAtOffset(JobSearchRequest request, int offset) {
        int pageSize = request.pageSize();
        JobCatalogSearchQuery query = new JobCatalogSearchQuery();
        query.setStatuses(List.of(CatalogJobStatus.ACTIVE.name()));
        query.setUseFullText(sqlDialect.supportsFullText());
        applyKeyword(query, request.keywords());
        query.setLocation(like(request.location()));
        query.setPostedAfter(postedAfter(request.postedWithinDays()));
        query.setSort(query.getKeyword() != null ? "RELEVANCE" : "POSTED_AT");
        query.setLimit(pageSize);
        query.setOffset(offset);

        long total = jobCatalogMapper.countSearch(query);
        List<JobCatalogEntry> entries = total == 0 ? List.of() : jobCatalogMapper.search(query);

        List<ProviderJobHit> hits = new ArrayList<>(entries.size());
        for (JobCatalogEntry entry : entries) {
            hits.add(new ProviderJobHit(
                    String.valueOf(entry.getId()),
                    entry.getTitle(),
                    entry.getCompanyName(),
                    entry.getLocationText(),
                    entry.getPostedAt() == null ? null : entry.getPostedAt().format(POSTED_AT),
                    entry.getDescriptionText(),
                    entry.getApplyUrl() != null ? entry.getApplyUrl() : entry.getCanonicalUrl(),
                    SOURCE,
                    SOURCE));
        }

        int nextOffset = offset + entries.size();
        boolean hasMore = nextOffset < total && !entries.isEmpty();
        return new ProviderSearchPage(
                hits,
                hasMore,
                total,
                hasMore ? String.valueOf(nextOffset) : null);
    }

    private void applyKeyword(JobCatalogSearchQuery query, String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return;
        }
        String keyword = keywords.strip();
        query.setKeyword(keyword);
        query.setKeywordLike(like(keyword));

        StringBuilder booleanQuery = new StringBuilder();
        for (String token : keyword.split("\\s+")) {
            String cleaned = token.replaceAll("[^\\p{L}\\p{N}_]", "");
            if (!cleaned.isEmpty()) {
                booleanQuery.append('+').append(cleaned).append('*').append(' ');
            }
        }
        String booleanForm = booleanQuery.toString().strip();
        query.setKeywordBoolean(booleanForm.isEmpty() ? null : booleanForm);
        if (query.isUseFullText() && query.getKeywordBoolean() == null) {
            query.setUseFullText(false);
        }
    }

    private LocalDateTime postedAfter(Integer postedWithinDays) {
        if (postedWithinDays == null) {
            return null;
        }
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(postedWithinDays);
    }

    private static String like(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String escaped = value.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static int parseOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.strip()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
