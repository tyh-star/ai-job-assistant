package aijob.service.impl;

import aijob.dto.JobCatalogDetail;
import aijob.jobsearch.ExternalJobIds;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.JobCatalogSummary;
import aijob.dto.SaveCatalogJobResponse;
import aijob.entity.Job;
import aijob.entity.User;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobDetailUnavailableException;
import aijob.exception.JobNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.freehire.FreeHireClient;
import aijob.freehire.FreeHireIgnoredParamJson;
import aijob.freehire.FreeHireJobJson;
import aijob.freehire.FreeHireMetaJson;
import aijob.freehire.FreeHireSearchResponseJson;
import aijob.mapper.JobMapper;
import aijob.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.util.MultiValueMap;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FreeHireJobCatalogService} with {@link FreeHireClient} mocked out -- so these
 * assertions are about the TRANSLATION this class does (request params it builds,
 * response fields it maps, save idempotency and per-user isolation) rather than about
 * HTTP, which {@code FreeHireClientTest} already covers against a real socket.
 */
class FreeHireJobCatalogServiceTest {

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";

    private static final Instant FIXED_NOW = Instant.parse("2026-09-04T12:00:00Z");

    private FreeHireClient client;
    private JobMapper jobMapper;
    private UserMapper userMapper;
    private FreeHireJobCatalogService service;

    @BeforeEach
    void setUp() {
        client = mock(FreeHireClient.class);
        jobMapper = mock(JobMapper.class);
        userMapper = mock(UserMapper.class);
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new FreeHireJobCatalogService(client, jobMapper, userMapper, clock);

        when(userMapper.findByEmail(ALICE)).thenReturn(user(1L, ALICE));
        when(userMapper.findByEmail(BOB)).thenReturn(user(2L, BOB));
    }

    private static User user(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    // ---------------------------------------------------------------------------
    // search: request -> FreeHire params
    // ---------------------------------------------------------------------------

    @Test
    void searchTranslatesEveryFacetToFreeHiresOwnParamNames() {
        stubSearch(emptyResult());

        service.search(ALICE, request().q("engineer").country("us").remoteType("REMOTE")
                .employmentType("FULL_TIME").postedWithinDays(7).page(1).size(20).sort("POSTED_AT").build());

        MultiValueMap<String, String> sent = capturedParams();
        assertEquals(List.of("engineer"), sent.get("q"));
        assertEquals(List.of("us"), sent.get("countries"), "countries must be lowercase, plural");
        assertEquals(List.of("remote"), sent.get("work_mode"));
        assertEquals(List.of("full_time"), sent.get("employment_type"));
        assertEquals(List.of("7"), sent.get("posted_within_days"));
        assertEquals(List.of("posted_at"), sent.get("sort"));
        assertEquals(List.of("desc"), sent.get("order"));
        assertEquals(List.of("20"), sent.get("limit"));
        assertEquals(List.of("0"), sent.get("offset"));
    }

    @Test
    void pageTwoComputesTheCorrectOffset() {
        stubSearch(emptyResult());

        service.search(ALICE, request().page(3).size(10).build());

        MultiValueMap<String, String> sent = capturedParams();
        assertEquals(List.of("20"), sent.get("offset"));
        assertEquals(List.of("10"), sent.get("limit"));
    }

    @Test
    void pageSizeIsCappedAtTheSharedMaximum() {
        stubSearch(emptyResult());

        service.search(ALICE, request().page(1).size(10_000).build());

        assertEquals(List.of(String.valueOf(JobCatalogSearchRequest.MAX_PAGE_SIZE)),
                capturedParams().get("limit"));
    }

    @Test
    void freeTextLocationIsRejectedNotSilentlyFoldedIntoTheTextQuery() {
        // Regression test for the old bug: folding location into `q` turned it into a
        // full-text match, so a job whose JD body merely MENTIONS a place (e.g. "our
        // Toronto office also hires remotely") could match a "Toronto" location filter
        // even though the posting's real location is somewhere else -- a false positive
        // masquerading as a working location filter. That can no longer happen because
        // this call never reaches the network at all: it must fail validation before any
        // params are built or any request sent.
        InvalidJobSearchException ex = assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                request().q("engineer").location("Toronto").build()));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("location"));
        verify(client, never()).search(any());
    }

    @Test
    void locationAloneIsAlsoRejectedNotSentAsATextSearch() {
        assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                request().location("Berlin").build()));
        verify(client, never()).search(any());
    }

    @Test
    void blankLocationIsNotTreatedAsAFilterAtAll() {
        stubSearch(emptyResult());

        service.search(ALICE, request().q("engineer").location("   ").build());

        assertEquals(List.of("engineer"), capturedParams().get("q"));
    }

    @Test
    void countrySelectionMapsToTheRealCountriesFacetNotFreeText() {
        stubSearch(emptyResult());

        service.search(ALICE, request().country("CA").build());

        MultiValueMap<String, String> sent = capturedParams();
        assertEquals(List.of("ca"), sent.get("countries"));
        // country filtering must never widen into an unrelated `regions` facet on its
        // own -- only what the caller explicitly asked for is ever sent.
        assertNull(sent.get("regions"));
        assertNull(sent.get("q"), "a bare country filter must not also become a text search");
    }

    // ---------------------------------------------------------------------------
    // city: the real fix for "location box accepts input but always 400s" --
    // a structured city facet instead of free text, and the countries/cities
    // OR-group discovered in FreeHire's own upstream source (query_filter.go).
    // ---------------------------------------------------------------------------

    @Test
    void citySelectionMapsToTheRealCitiesFacet() {
        stubSearch(emptyResult());

        service.search(ALICE, request().city("Toronto").build());

        MultiValueMap<String, String> sent = capturedParams();
        assertEquals(List.of("Toronto"), sent.get("cities"), "sent verbatim -- never re-cased or reformatted");
        assertNull(sent.get("q"), "a bare city filter must not also become a text search");
    }

    @Test
    void citySelectionNeverAlsoSendsCountryEvenIfBothWereSomehowSet() {
        // See FreeHireJobCatalogService#buildSearchParams: countries/regions/cities share
        // ONE OR-group upstream (confirmed against the pinned upstream source), so
        // "countries=ca AND cities=Toronto" in the same request would NOT mean "Toronto,
        // Canada" -- it would mean "anywhere in Canada, OR anywhere named Toronto",
        // silently widening the search. cities is also a bare name match with no country
        // of its own (see cityFilterMatchesByNameOnlyAcrossCountries in
        // FreeHireEndToEndControllerTest), so there is no combination of the two facets
        // that would narrow to "Toronto, Canada" -- this must never reach the network as
        // a combined countries+cities request regardless.
        stubSearch(emptyResult());

        InvalidJobSearchException ex = assertThrows(InvalidJobSearchException.class,
                () -> service.search(ALICE, request().city("Toronto").country("US").build()));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("city")
                && ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("country"));
        verify(client, never()).search(any());
    }

    @Test
    void blankCityIsNotTreatedAsAFilterAtAllAndCountryStillWorks() {
        stubSearch(emptyResult());

        service.search(ALICE, request().city("   ").country("CA").build());

        MultiValueMap<String, String> sent = capturedParams();
        assertEquals(List.of("ca"), sent.get("countries"));
        assertNull(sent.get("cities"));
    }

    @Test
    void cityCountryIsNeverSentToFreeHireEvenThoughItIsAccepted() {
        // cityCountry is site-local display metadata (see
        // JobCatalogSearchRequest#cityCountry's javadoc) -- accepted alongside city, but
        // must never reach FreeHire as `countries`, which would silently widen the
        // search via the same OR-group as the rejected city+country combination above.
        stubSearch(new FreeHireSearchResponseJson(
                List.of(job("tor-1", "https://x/1", "Data Engineer", "Acme", "Toronto, Canada",
                        "<p>d</p>", List.of("ca"), List.of(), "hybrid", List.of("Toronto"))),
                new FreeHireMetaJson(1, 20, 0, null)));

        service.search(ALICE, request().city("Toronto").cityCountry("CA").build());

        MultiValueMap<String, String> sent = capturedParams();
        assertEquals(List.of("Toronto"), sent.get("cities"));
        assertNull(sent.get("countries"), "cityCountry must never become a countries param");
        assertNull(sent.get("cityCountry"), "cityCountry is never a FreeHire query param at all");
    }

    @Test
    void cityMatchIsConfirmedWhenTheHitsOwnCountriesBacksUpTheSuggestion() {
        stubSearch(new FreeHireSearchResponseJson(
                List.of(job("tor-clean", "https://x/1", "Data Engineer", "Acme", "Toronto, Canada",
                        "<p>d</p>", List.of("ca"), List.of(), "hybrid", List.of("Toronto"))),
                new FreeHireMetaJson(1, 20, 0, null)));

        JobCatalogSearchResponse response = service.search(ALICE, request().city("Toronto").cityCountry("CA").build());

        JobCatalogSummary.CityMatch match = response.results().get(0).cityMatch();
        assertEquals("Toronto", match.city());
        assertEquals("CA", match.countryCode());
        assertTrue(match.countryConfirmed());
    }

    @Test
    void cityMatchIsPresentButNotConfirmedWhenAGenuineMultiLocationPostingsOwnCountryDiffers() {
        // The real bug this round: a multi-location posting (confirmed live against real
        // freehire.me on 2026-09-06) whose own `countries` is "us", not "ca", even though
        // its `cities` array genuinely contains "Toronto". Must never be presented as
        // confirmed "Toronto, Canada" -- but must also never be silently hidden.
        stubSearch(new FreeHireSearchResponseJson(
                List.of(job("tor-multi-us", "https://x/2", "Brand Manager", "RemoteCo", "Remote (US)",
                        "<p>d</p>", List.of("us"), List.of(), "remote", List.of("New York City", "Toronto"))),
                new FreeHireMetaJson(1, 20, 0, null)));

        JobCatalogSearchResponse response = service.search(ALICE, request().city("Toronto").cityCountry("CA").build());

        JobCatalogSummary summary = response.results().get(0);
        assertEquals("Remote (US)", summary.locationText(), "the posting's own location must never be hidden");
        assertEquals("US", summary.countryCode());
        JobCatalogSummary.CityMatch match = summary.cityMatch();
        assertEquals("Toronto", match.city(), "cities does contain Toronto -- cityMatch must still be present");
        assertFalse(match.countryConfirmed(), "this hit's own countries is us, not ca -- must not claim CA");
    }

    @Test
    void cityMatchIsAbsentWhenTheSearchWasNotCityScopedAtAll() {
        stubSearch(new FreeHireSearchResponseJson(
                List.of(job("plain", "https://x/3", "Engineer", "Acme", "Remote - US",
                        "<p>d</p>", List.of("us"), List.of(), "remote", List.of())),
                new FreeHireMetaJson(1, 20, 0, null)));

        JobCatalogSearchResponse response = service.search(ALICE, request().country("US").build());

        assertNull(response.results().get(0).cityMatch());
    }

    @Test
    void searchCitiesDelegatesToFreeHireClientAndMapsTheWireShape() {
        when(client.searchCities("Toronto", "ca"))
                .thenReturn(List.of(new aijob.freehire.FreeHireGeoCityJson("Toronto", "ca")));

        List<aijob.dto.CitySuggestion> suggestions = service.searchCities("Toronto", "CA");

        assertEquals(1, suggestions.size());
        assertEquals("Toronto", suggestions.get(0).value());
        assertEquals("ca", suggestions.get(0).countryCode());
        verify(client).searchCities("Toronto", "ca");
    }

    @Test
    void searchCitiesWithABlankQueryNeverCallsFreeHire() {
        assertEquals(List.of(), service.searchCities("", null));
        assertEquals(List.of(), service.searchCities(null, "CA"));
        verify(client, never()).searchCities(any(), any());
    }

    @Test
    void searchCitiesRejectsAnInvalidCountryCodeBeforeAnyNetworkCall() {
        assertThrows(InvalidJobSearchException.class, () -> service.searchCities("Toronto", "CANADA"));
        verify(client, never()).searchCities(any(), any());
    }

    @Test
    void employmentTypesWithNoFreeHireEquivalentAreRejectedNotSilentlyOmitted() {
        // Regression test: silently dropping the facet made an unsupported selection
        // LOOK honoured while the search actually ran unfiltered by employment type --
        // exactly the "silently ignored filter" this must never do. It must now fail
        // validation before any network call, for both TEMPORARY and OTHER.
        InvalidJobSearchException ex = assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                request().employmentType("TEMPORARY").build()));
        assertTrue(ex.getMessage().contains("TEMPORARY"));
        verify(client, never()).search(any());

        assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                request().employmentType("OTHER").build()));
        verify(client, never()).search(any());
    }

    @Test
    void relevanceSortSendsNoSortParamAtAll() {
        stubSearch(emptyResult());

        service.search(ALICE, request().q("x").sort("RELEVANCE").build());

        assertNull(capturedParams().get("sort"));
    }

    @Test
    void invalidRemoteTypeIsRejectedBeforeAnyNetworkCall() {
        assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                request().remoteType("ANYWHERE").build()));
        verify(client, never()).search(any());
    }

    @Test
    void invalidCountryIsRejected() {
        assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                request().country("CANADA").build()));
    }

    @Test
    void invalidPostedWithinDaysIsRejected() {
        assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                request().postedWithinDays(9).build()));
    }

    // ---------------------------------------------------------------------------
    // search: FreeHire response -> summaries, and ignored_params visibility
    // ---------------------------------------------------------------------------

    @Test
    void searchMapsTheFullResultShapeIncludingIdentityAndLinks() throws Exception {
        FreeHireJobJson hit = job("acme-swe-x1", "https://boards.greenhouse.io/acme/jobs/1",
                "Software Engineer", "Acme", "Remote - US", "<p>Full JD body here.</p>",
                List.of("us"), List.of("remote"), "remote");
        stubSearch(new FreeHireSearchResponseJson(List.of(hit), new FreeHireMetaJson(1, 20, 0, null)));

        JobCatalogSearchResponse response = service.search(ALICE, blankRequest());

        assertEquals(1, response.results().size());
        JobCatalogSummary summary = response.results().get(0);
        assertEquals("freehire:acme-swe-x1", summary.id(), "must carry the freehire: namespace, never a bare id");
        assertEquals("Software Engineer", summary.title());
        assertEquals("Acme", summary.companyName());
        assertEquals("https://boards.greenhouse.io/acme/jobs/1", summary.canonicalUrl());
        assertEquals("US", summary.countryCode());
        assertEquals("REMOTE", summary.remoteType());
        assertEquals("ACTIVE", summary.status());
        assertFalse(summary.saved());
        assertNull(summary.personalJobId());

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode json = mapper.valueToTree(summary);
        assertFalse(json.has("applyUrl"), "apply URL belongs on detail, never on the search row");
        assertFalse(json.has("descriptionSnippet"));
        assertFalse(json.has("descriptionText"));
        assertFalse(json.has("descriptionHtml"));
        assertFalse(mapper.writeValueAsString(summary).contains("Full JD body"));
    }

    @Test
    void ignoredParamsAreSurfacedAsAWarningNotHiddenAndDoNotChangeTheResult() {
        FreeHireMetaJson meta = new FreeHireMetaJson(5, 20, 0,
                List.of(new FreeHireIgnoredParamJson("country", "countries")));
        stubSearch(new FreeHireSearchResponseJson(List.of(), meta));

        // Must not throw, must not silently "fix" the request -- the total FreeHire
        // actually reports (for whatever it DID filter on) is what the caller sees.
        JobCatalogSearchResponse response = service.search(ALICE, blankRequest());
        assertEquals(5, response.totalCount());
    }

    @Test
    void emptyHitsProduceAnEmptyPageNotAnError() {
        stubSearch(emptyResult());
        JobCatalogSearchResponse response = service.search(ALICE, blankRequest());
        assertTrue(response.results().isEmpty());
        assertEquals(0, response.totalCount());
    }

    @Test
    void aDangerousUrlSchemeNeverReachesTheSummary() {
        FreeHireJobJson hit = job("x", "javascript:alert(1)", "Title", "Co", "Remote",
                "<p>JD</p>", List.of(), List.of(), null);
        stubSearch(new FreeHireSearchResponseJson(List.of(hit), new FreeHireMetaJson(1, 20, 0, null)));

        JobCatalogSummary summary = service.search(ALICE, blankRequest()).results().get(0);
        assertNull(summary.canonicalUrl());
    }

    @Test
    void aSearchHitAlreadySavedByThisUserIsMarkedSaved() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "T", "C", "R",
                "<p>d</p>", List.of(), List.of(), null);
        stubSearch(new FreeHireSearchResponseJson(List.of(hit), new FreeHireMetaJson(1, 20, 0, null)));
        // The row's own externalJobId is whatever the REAL column actually stores -- the
        // PERSISTED (hashed) id, never the raw slug -- see
        // FreeHireJobCatalogService#savedByExternalId's javadoc for the reverse-mapping
        // back to the raw slug this asserts against.
        when(jobMapper.findSavedExternalJobIds(eq(1L), eq(FreeHireJobCatalogService.SOURCE), anyList()))
                .thenReturn(List.of(new JobMapper.ExternalIdAndPersonalJobId(persistId("acme-swe-x1"), 77L, false, null)));

        JobCatalogSummary summary = service.search(ALICE, blankRequest()).results().get(0);
        assertTrue(summary.saved());
        assertEquals(77L, summary.personalJobId());
        assertFalse(summary.applied());
    }

    @Test
    void aSearchHitAlreadyAppliedByThisUserIsMarkedApplied() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "T", "C", "R",
                "<p>d</p>", List.of(), List.of(), null);
        stubSearch(new FreeHireSearchResponseJson(List.of(hit), new FreeHireMetaJson(1, 20, 0, null)));
        LocalDateTime appliedAt = LocalDateTime.of(2026, 9, 3, 10, 0);
        when(jobMapper.findSavedExternalJobIds(eq(1L), eq(FreeHireJobCatalogService.SOURCE), anyList()))
                .thenReturn(List.of(new JobMapper.ExternalIdAndPersonalJobId(persistId("acme-swe-x1"), 77L, true, appliedAt)));

        JobCatalogSummary summary = service.search(ALICE, blankRequest()).results().get(0);
        assertTrue(summary.applied());
        assertEquals(appliedAt, summary.appliedAt());
    }

    @Test
    void anonymousSearchNeverLooksUpAnyUsersSavedOrAppliedState() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "T", "C", "R",
                "<p>d</p>", List.of(), List.of(), null);
        stubSearch(new FreeHireSearchResponseJson(List.of(hit), new FreeHireMetaJson(1, 20, 0, null)));

        JobCatalogSummary summary = service.search(null, blankRequest()).results().get(0);

        assertFalse(summary.saved());
        assertNull(summary.personalJobId());
        assertFalse(summary.applied());
        assertNull(summary.appliedAt());
        Mockito.verifyNoInteractions(userMapper);
        verify(jobMapper, never()).findSavedExternalJobIds(any(), any(), anyList());
    }

    @Test
    void anonymousDetailAlsoNeverLooksUpSavedOrAppliedState() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "Title", "Co", "Remote",
                "<p>Safe</p>", List.of("de"), List.of("europe"), "hybrid");
        when(client.getBySlug("acme-swe-x1")).thenReturn(hit);

        JobCatalogDetail detail = service.findDetail(null, "freehire:acme-swe-x1");

        assertFalse(detail.saved());
        assertNull(detail.personalJobId());
        assertFalse(detail.applied());
        Mockito.verifyNoInteractions(userMapper);
        verify(jobMapper, never()).findByUserIdAndSourceAndExternalJobId(any(), any(), any());
    }

    // ---------------------------------------------------------------------------
    // detail
    // ---------------------------------------------------------------------------

    @Test
    void findDetailRequiresTheFreehirePrefix() {
        assertThrows(JobNotFoundException.class, () -> service.findDetail(ALICE, "42"));
        assertThrows(JobNotFoundException.class, () -> service.findDetail(ALICE, "local:42"));
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void findDetailPropagatesTheUpstreamDetailUnavailableExceptionDistinctFromThisSitesOwnNotFound() {
        // A real, expected scenario (confirmed live against freehire.me): a posting
        // still listed in search results moments earlier can 404 on its own detail
        // lookup. This is never the SAME exception type as findDetailRequiresTheFreehirePrefix's
        // "this site rejects the reference" case above -- confusing the two would make
        // the frontend show a misleading message for either.
        when(client.getBySlug("acme-swe-gone")).thenThrow(
                new JobDetailUnavailableException(JobDetailUnavailableException.CODE));

        JobDetailUnavailableException ex = assertThrows(JobDetailUnavailableException.class,
                () -> service.findDetail(ALICE, "freehire:acme-swe-gone"));
        assertEquals(JobDetailUnavailableException.CODE, ex.getMessage());
    }

    @Test
    void findDetailFetchesBySlugAndSanitizesHtml() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "Title", "Co", "Remote",
                "<p>Safe</p><script>evil()</script>", List.of("de"), List.of("europe"), "hybrid");
        when(client.getBySlug("acme-swe-x1")).thenReturn(hit);
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(null);

        JobCatalogDetail detail = service.findDetail(ALICE, "freehire:acme-swe-x1");

        assertEquals("freehire:acme-swe-x1", detail.id());
        assertTrue(detail.descriptionHtml().contains("<p>Safe</p>"));
        assertFalse(detail.descriptionHtml().contains("<script"));
        assertEquals("DE", detail.countryCode());
        assertEquals("HYBRID", detail.remoteType());
        assertEquals("https://x.example/1", detail.applyUrl());
        assertTrue(detail.descriptionText().contains("Safe"));
        assertFalse(detail.saved());
    }

    // ---------------------------------------------------------------------------
    // save: identity, idempotency, per-user isolation
    // ---------------------------------------------------------------------------

    @Test
    void saveRejectsAReferenceMissingTheFreehirePrefix() {
        assertThrows(JobNotFoundException.class, () -> service.saveToMyJobs(ALICE, "42"));
        Mockito.verifyNoInteractions(client);
    }

    // ---------------------------------------------------------------------------
    // requireSlug's own input validation -- malformed input is a 400
    // (InvalidJobSearchException), never confused with a 404 (this site's own
    // "not found") or JobDetailUnavailableException (a REAL upstream 404, which a
    // malformed slug must never even reach FreeHire to trigger).
    // ---------------------------------------------------------------------------

    @Test
    void aSlugWithLeadingOrTrailingWhitespaceIs400NotNotFound() {
        assertThrows(InvalidJobSearchException.class,
                () -> service.saveToMyJobs(ALICE, "freehire: acme-swe-x1"));
        assertThrows(InvalidJobSearchException.class,
                () -> service.saveToMyJobs(ALICE, "freehire:acme-swe-x1 "));
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void aSlugContainingAPathSeparatorIs400NotNotFound() {
        assertThrows(InvalidJobSearchException.class,
                () -> service.saveToMyJobs(ALICE, "freehire:acme/../etc"));
        assertThrows(InvalidJobSearchException.class,
                () -> service.saveToMyJobs(ALICE, "freehire:acme\\swe"));
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void anAbsurdlyLongSlugIs400NotNotFound() {
        String tooLong = "x".repeat(3000);
        assertThrows(InvalidJobSearchException.class,
                () -> service.saveToMyJobs(ALICE, "freehire:" + tooLong));
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void aGenuinelyLongButPlausibleSlugIsNeverRejectedForLengthAlone() {
        // The actual fix: this must NOT throw at all on account of length -- only the
        // persisted (hashed) id has a width limit, never the raw slug. 77 characters is
        // the exact real length confirmed live against freehire.me.
        String longSlug = "magento-2-frontend-developer-hyva-experience-required-unotech-global-bptpehnr";
        assertEquals(77, longSlug.length());
        FreeHireJobJson hit = job(longSlug, "https://x.example/1", "Title", "Acme", "Remote",
                "<p>d</p>", List.of("in"), List.of(), null);
        when(client.getBySlug(longSlug)).thenReturn(hit);
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId(longSlug)))
                .thenReturn(null);
        stubInsertAssigningId(101L);
        when(jobMapper.findById(101L)).thenReturn(personalJob(101L, 1L, persistId(longSlug), "Title", "Acme"));

        SaveCatalogJobResponse response = service.saveToMyJobs(ALICE, "freehire:" + longSlug);

        assertFalse(response.alreadySaved());
        verify(client).getBySlug(longSlug);
    }

    @Test
    void savingAPostingWhoseUpstreamDetailHas404dNeverInsertsAPartialRow() {
        when(client.getBySlug("acme-swe-gone")).thenThrow(
                new JobDetailUnavailableException(JobDetailUnavailableException.CODE));

        JobDetailUnavailableException ex = assertThrows(JobDetailUnavailableException.class,
                () -> service.saveToMyJobs(ALICE, "freehire:acme-swe-gone"));
        assertEquals(JobDetailUnavailableException.CODE, ex.getMessage());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void savingForTheFirstTimeFetchesFromFreeHireAndInsertsOnePersonalJob() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "Title", "Acme", "Remote",
                "<p>Full JD.</p>", List.of("us"), List.of(), "remote");
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(null);
        when(client.getBySlug("acme-swe-x1")).thenReturn(hit);
        stubInsertAssigningId(101L);
        Job persisted = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        when(jobMapper.findById(101L)).thenReturn(persisted);

        SaveCatalogJobResponse response = service.saveToMyJobs(ALICE, "freehire:acme-swe-x1");

        assertFalse(response.alreadySaved());
        assertEquals(101L, response.jobId());
        assertEquals("freehire:acme-swe-x1", response.catalogJobId());

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobMapper).insert(captor.capture());
        Job inserted = captor.getValue();
        assertEquals(FreeHireJobCatalogService.SOURCE, inserted.getSource());
        // The PERSISTED id (a SHA-256 hex digest), never the raw slug -- see
        // FreeHireJobCatalogService#persistId's javadoc for why storing the raw slug
        // directly is what let a real 79-character slug overflow VARCHAR(64).
        assertEquals(persistId("acme-swe-x1"), inserted.getExternalJobId());
        assertEquals(64, inserted.getExternalJobId().length());
        assertEquals(1L, inserted.getUserId());
    }

    @Test
    void repeatingTheSaveIsIdempotentAndMakesNoSecondNetworkCall() {
        Job existing = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(existing);

        SaveCatalogJobResponse response = service.saveToMyJobs(ALICE, "freehire:acme-swe-x1");

        assertTrue(response.alreadySaved());
        assertEquals(101L, response.jobId());
        verify(client, never()).getBySlug(any());
        verify(jobMapper, never()).insert(any());
    }

    // ---------------------------------------------------------------------------
    // Rolling-upgrade compatibility: a row saved before the persistId fix stored the
    // raw slug directly in external_job_id. See
    // FreeHireJobCatalogService#findExistingByPersistedOrLegacyId's javadoc.
    // ---------------------------------------------------------------------------

    @Test
    void saveFindsALegacyRawSlugRowWhenThePersistedIdLookupMisses() {
        // The persisted-id lookup misses (nothing saved under the NEW identity yet)...
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("legacy-slug")))
                .thenReturn(null);
        // ...but the raw slug itself matches a row saved before this fix existed.
        Job legacyRow = personalJob(55L, 1L, "legacy-slug", "Legacy Title", "Acme");
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, "legacy-slug"))
                .thenReturn(legacyRow);

        SaveCatalogJobResponse response = service.saveToMyJobs(ALICE, "freehire:legacy-slug");

        assertTrue(response.alreadySaved());
        assertEquals(55L, response.jobId());
        verify(client, never()).getBySlug(any());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void clearAppliedFindsALegacyRawSlugRowWhenThePersistedIdLookupMisses() {
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("legacy-slug")))
                .thenReturn(null);
        Job legacyRow = personalJob(55L, 1L, "legacy-slug", "Legacy Title", "Acme");
        legacyRow.setApplied(true);
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, "legacy-slug"))
                .thenReturn(legacyRow);
        Job afterClear = personalJob(55L, 1L, "legacy-slug", "Legacy Title", "Acme");
        when(jobMapper.findById(55L)).thenReturn(afterClear);

        SaveCatalogJobResponse response = service.clearApplied(ALICE, "freehire:legacy-slug");

        assertFalse(response.job().isApplied());
        verify(jobMapper).clearApplied(55L, 1L);
    }

    @Test
    void aSearchHitMatchingALegacyRawSlugRowIsMarkedSaved() {
        FreeHireJobJson hit = job("legacy-slug", "https://x.example/1", "T", "C", "R",
                "<p>d</p>", List.of(), List.of(), null);
        stubSearch(new FreeHireSearchResponseJson(List.of(hit), new FreeHireMetaJson(1, 20, 0, null)));
        // The row this returns stores the RAW slug as its externalJobId (not hashed) --
        // exactly what the real column holds for a pre-existing legacy row.
        when(jobMapper.findSavedExternalJobIds(eq(1L), eq(FreeHireJobCatalogService.SOURCE), anyList()))
                .thenReturn(List.of(new JobMapper.ExternalIdAndPersonalJobId("legacy-slug", 55L, false, null)));

        JobCatalogSummary summary = service.search(ALICE, blankRequest()).results().get(0);

        assertTrue(summary.saved());
        assertEquals(55L, summary.personalJobId());
    }

    @Test
    void twoUsersSavingTheSamePostingGetIndependentPersonalJobs() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "Title", "Acme", "Remote",
                "<p>d</p>", List.of(), List.of(), null);
        when(client.getBySlug("acme-swe-x1")).thenReturn(hit);
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(null);
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(2L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(null);
        stubInsertAssigningId(101L);
        when(jobMapper.findById(101L)).thenReturn(personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme"));

        service.saveToMyJobs(ALICE, "freehire:acme-swe-x1");

        stubInsertAssigningId(202L);
        when(jobMapper.findById(202L)).thenReturn(personalJob(202L, 2L, "acme-swe-x1", "Title", "Acme"));
        SaveCatalogJobResponse forBob = service.saveToMyJobs(BOB, "freehire:acme-swe-x1");

        assertEquals(202L, forBob.jobId());
        // Each user's lookup was scoped to their own id -- Bob's fetch never saw Alice's row.
        verify(jobMapper).findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1"));
        verify(jobMapper).findByUserIdAndSourceAndExternalJobId(2L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1"));
    }

    @Test
    void unknownUserIsRejected() {
        when(userMapper.findByEmail("ghost@example.com")).thenReturn(null);
        assertThrows(UserNotFoundException.class,
                () -> service.search("ghost@example.com", blankRequest()));
    }

    // ---------------------------------------------------------------------------
    // applied: confirm, revoke, idempotency, per-user isolation
    // ---------------------------------------------------------------------------

    @Test
    void markAppliedSavesFirstWhenNotYetSavedThenMarksApplied() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "Title", "Acme", "Remote",
                "<p>Full JD.</p>", List.of("us"), List.of(), "remote");
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(null);
        when(client.getBySlug("acme-swe-x1")).thenReturn(hit);
        stubInsertAssigningId(101L);
        Job afterSave = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        Job afterApply = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        afterApply.setApplied(true);
        afterApply.setAppliedAt(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        // findById is called once right after the insert (still un-applied) and again
        // after markApplied (now applied) -- two different snapshots of the same row.
        when(jobMapper.findById(101L)).thenReturn(afterSave, afterApply);

        SaveCatalogJobResponse response = service.markApplied(ALICE, "freehire:acme-swe-x1");

        assertEquals(101L, response.jobId());
        assertTrue(response.job().isApplied());
        assertEquals(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC), response.job().getAppliedAt());
        verify(jobMapper).markApplied(101L, 1L, LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        verify(client, times(1)).getBySlug("acme-swe-x1");
    }

    @Test
    void markAppliedOnAnAlreadySavedJobSkipsTheFreeHireFetch() {
        Job existing = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(existing);
        Job afterApply = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        afterApply.setApplied(true);
        afterApply.setAppliedAt(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        when(jobMapper.findById(101L)).thenReturn(afterApply);

        SaveCatalogJobResponse response = service.markApplied(ALICE, "freehire:acme-swe-x1");

        assertTrue(response.job().isApplied());
        verify(client, never()).getBySlug(any());
        verify(jobMapper, never()).insert(any());
        verify(jobMapper).markApplied(101L, 1L, LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void confirmingAppliedTwiceIsIdempotentAndJustRefreshesTheTimestamp() {
        Job existing = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(existing);
        Job afterApply = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        afterApply.setApplied(true);
        afterApply.setAppliedAt(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        when(jobMapper.findById(101L)).thenReturn(afterApply);

        service.markApplied(ALICE, "freehire:acme-swe-x1");
        service.markApplied(ALICE, "freehire:acme-swe-x1");

        // Two confirmations, two UPDATEs (a real refresh each time), never a duplicate
        // personal job and never an exception -- confirming again is a safe no-op that
        // just rewrites the same flag and a fresh timestamp.
        verify(jobMapper, times(2))
                .markApplied(101L, 1L, LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void clearAppliedRevokesAMistakenConfirmation() {
        Job existing = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        existing.setApplied(true);
        existing.setAppliedAt(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(existing);
        Job afterClear = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        when(jobMapper.findById(101L)).thenReturn(afterClear);

        SaveCatalogJobResponse response = service.clearApplied(ALICE, "freehire:acme-swe-x1");

        assertFalse(response.job().isApplied());
        assertNull(response.job().getAppliedAt());
        verify(jobMapper).clearApplied(101L, 1L);
    }

    @Test
    void clearAppliedOnANeverSavedJobIsNotFound() {
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(null);

        assertThrows(JobNotFoundException.class, () -> service.clearApplied(ALICE, "freehire:acme-swe-x1"));
        verify(jobMapper, never()).clearApplied(any(), any());
    }

    @Test
    void twoUsersApplyingToTheSamePostingAreScopedIndependently() {
        Job aliceExisting = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        Job bobExisting = personalJob(202L, 2L, "acme-swe-x1", "Title", "Acme");
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(aliceExisting);
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(2L, FreeHireJobCatalogService.SOURCE, persistId("acme-swe-x1")))
                .thenReturn(bobExisting);
        when(jobMapper.findById(101L)).thenReturn(aliceExisting);
        when(jobMapper.findById(202L)).thenReturn(bobExisting);

        service.markApplied(ALICE, "freehire:acme-swe-x1");
        service.markApplied(BOB, "freehire:acme-swe-x1");

        // Each confirmation is scoped by that user's own id -- Bob's confirmation can
        // never mark Alice's row (or vice versa), even though both point at the same
        // underlying FreeHire posting.
        verify(jobMapper).markApplied(eq(101L), eq(1L), any());
        verify(jobMapper).markApplied(eq(202L), eq(2L), any());
    }

    // ---------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------

    private void stubSearch(FreeHireSearchResponseJson response) {
        when(client.search(any())).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private MultiValueMap<String, String> capturedParams() {
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(client, times(1)).search(captor.capture());
        return captor.getValue();
    }

    private void stubInsertAssigningId(long id) {
        Mockito.doAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            job.setId(id);
            return 1;
        }).when(jobMapper).insert(any());
    }

    private static Job personalJob(Long id, Long userId, String externalId, String title, String company) {
        Job job = new Job();
        job.setId(id);
        job.setUserId(userId);
        job.setExternalJobId(externalId);
        job.setTitle(title);
        job.setCompany(company);
        job.setSource(FreeHireJobCatalogService.SOURCE);
        job.setStatus("SAVED");
        return job;
    }

    /**
     * The identity {@code jobs.external_job_id} actually stores for a FreeHire slug --
     * see {@code FreeHireJobCatalogService#persistId}'s javadoc. Every stub/verify
     * against {@code jobMapper.findByUserIdAndSourceAndExternalJobId} below goes through
     * this, never the raw slug directly, matching production exactly.
     */
    private static String persistId(String slug) {
        return ExternalJobIds.persistId(FreeHireJobCatalogService.SOURCE, slug);
    }

    private static JobCatalogSearchRequest blankRequest() {
        return new JobCatalogSearchRequest(null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * A tiny fluent builder so a test only names the fields it actually cares about --
     * {@code JobCatalogSearchRequest} is a positional record, and a bare 10-argument
     * constructor call at every call site is exactly the kind of thing a future field
     * addition silently breaks in the wrong place.
     */
    private static RequestBuilder request() {
        return new RequestBuilder();
    }

    private static final class RequestBuilder {
        private String q;
        private String location;
        private String city;
        private String cityCountry;
        private String country;
        private String remoteType;
        private String employmentType;
        private Integer postedWithinDays;
        private Integer page;
        private Integer size;
        private String sort;

        RequestBuilder q(String v) { this.q = v; return this; }
        RequestBuilder location(String v) { this.location = v; return this; }
        RequestBuilder city(String v) { this.city = v; return this; }
        RequestBuilder cityCountry(String v) { this.cityCountry = v; return this; }
        RequestBuilder country(String v) { this.country = v; return this; }
        RequestBuilder remoteType(String v) { this.remoteType = v; return this; }
        RequestBuilder employmentType(String v) { this.employmentType = v; return this; }
        RequestBuilder postedWithinDays(Integer v) { this.postedWithinDays = v; return this; }
        RequestBuilder page(Integer v) { this.page = v; return this; }
        RequestBuilder size(Integer v) { this.size = v; return this; }
        RequestBuilder sort(String v) { this.sort = v; return this; }

        JobCatalogSearchRequest build() {
            return new JobCatalogSearchRequest(
                    q, location, city, cityCountry, country, remoteType, employmentType,
                    postedWithinDays, page, size, sort);
        }
    }

    private static FreeHireSearchResponseJson emptyResult() {
        return new FreeHireSearchResponseJson(List.of(), new FreeHireMetaJson(0, 20, 0, null));
    }

    private static FreeHireJobJson job(String slug, String url, String title, String company, String location,
                                       String description, List<String> countries, List<String> regions,
                                       String workMode) {
        return job(slug, url, title, company, location, description, countries, regions, workMode, List.of());
    }

    private static FreeHireJobJson job(String slug, String url, String title, String company, String location,
                                       String description, List<String> countries, List<String> regions,
                                       String workMode, List<String> cities) {
        return new FreeHireJobJson(
                slug, "greenhouse", "ext-" + slug, url, title, company, company.toLowerCase(),
                location, description, countries, regions, workMode, cities, List.of(), "tech",
                "2026-09-01T00:00:00Z", "2026-08-30T00:00:00Z", null);
    }
}
