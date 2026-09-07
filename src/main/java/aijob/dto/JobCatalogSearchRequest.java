package aijob.dto;

import java.util.Set;

/**
 * User-supplied catalog search parameters, before normalization.
 *
 * <p>{@code location} is a free-text filter meaningful only to {@code local_catalog}
 * (a LIKE match over its own collected data). {@code city} is a structured facet value
 * -- under FreeHire, exactly one of the {@code value} strings {@code GET /geo/cities}
 * returns (see {@code FreeHireClient#searchCities}), never free text -- so a caller can
 * never send a city FreeHire's index does not carry. The two are independent: a
 * provider is never asked to honour both a free-text location AND a structured city in
 * the same request.
 *
 * <p>{@code cityCountry} is the country code that came along with {@code city} when the
 * caller picked it from a {@code GET /geo/cities} suggestion (e.g. picking "Toronto"
 * whose suggestion said {@code country: "ca"}). It is ONLY ever used to label results
 * for display -- see {@code FreeHireJobCatalogService#toSummaries}'s {@code cityMatch}
 * construction -- and is NEVER sent to FreeHire as {@code countries} alongside {@code
 * cities}: see {@code FreeHireJobCatalogService#buildSearchParams}'s javadoc for why
 * that would silently widen rather than narrow the search. It carries no authority over
 * what the search actually returns; it is this site's own record of what the user
 * picked, used afterward to check whether a given result's own {@code countries} field
 * actually confirms it.
 */
public record JobCatalogSearchRequest(
        String q,
        String location,
        String city,
        String cityCountry,
        String country,
        String remoteType,
        String employmentType,
        Integer postedWithinDays,
        Integer page,
        Integer size,
        String sort
) {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;
    public static final Set<Integer> ALLOWED_POSTED_WITHIN_DAYS = Set.of(1, 3, 7, 14, 30);
    public static final String SORT_RELEVANCE = "RELEVANCE";
    public static final String SORT_POSTED_AT = "POSTED_AT";
}
