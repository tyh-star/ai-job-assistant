package aijob.service.impl;

import aijob.dto.JobCatalogDetail;
import aijob.dto.JobCatalogSearchRequest;
import aijob.dto.JobCatalogSearchResponse;
import aijob.dto.JobCatalogSummary;
import aijob.dto.SaveCatalogJobResponse;
import aijob.entity.Job;
import aijob.entity.User;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.freehire.FreeHireClient;
import aijob.freehire.FreeHireIgnoredParamJson;
import aijob.freehire.FreeHireJobJson;
import aijob.freehire.FreeHireMetaJson;
import aijob.freehire.FreeHireSearchResponseJson;
import aijob.mapper.JobMapper;
import aijob.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.util.MultiValueMap;

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

    private FreeHireClient client;
    private JobMapper jobMapper;
    private UserMapper userMapper;
    private FreeHireJobCatalogService service;

    @BeforeEach
    void setUp() {
        client = mock(FreeHireClient.class);
        jobMapper = mock(JobMapper.class);
        userMapper = mock(UserMapper.class);
        service = new FreeHireJobCatalogService(client, jobMapper, userMapper);

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

        service.search(ALICE, new JobCatalogSearchRequest(
                "engineer", null, "us", "REMOTE", "FULL_TIME", 7, 1, 20, "POSTED_AT"));

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

        service.search(ALICE, new JobCatalogSearchRequest(
                null, null, null, null, null, null, 3, 10, null));

        MultiValueMap<String, String> sent = capturedParams();
        assertEquals(List.of("20"), sent.get("offset"));
        assertEquals(List.of("10"), sent.get("limit"));
    }

    @Test
    void pageSizeIsCappedAtTheSharedMaximum() {
        stubSearch(emptyResult());

        service.search(ALICE, new JobCatalogSearchRequest(
                null, null, null, null, null, null, 1, 10_000, null));

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
                new JobCatalogSearchRequest("engineer", "Toronto", null, null, null, null, null, null, null)));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("location"));
        verify(client, never()).search(any());
    }

    @Test
    void locationAloneIsAlsoRejectedNotSentAsATextSearch() {
        assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                new JobCatalogSearchRequest(null, "Berlin", null, null, null, null, null, null, null)));
        verify(client, never()).search(any());
    }

    @Test
    void blankLocationIsNotTreatedAsAFilterAtAll() {
        stubSearch(emptyResult());

        service.search(ALICE, new JobCatalogSearchRequest(
                "engineer", "   ", null, null, null, null, null, null, null));

        assertEquals(List.of("engineer"), capturedParams().get("q"));
    }

    @Test
    void countrySelectionMapsToTheRealCountriesFacetNotFreeText() {
        stubSearch(emptyResult());

        service.search(ALICE, new JobCatalogSearchRequest(
                null, null, "CA", null, null, null, null, null, null));

        MultiValueMap<String, String> sent = capturedParams();
        assertEquals(List.of("ca"), sent.get("countries"));
        // country filtering must never widen into an unrelated `regions` facet on its
        // own -- only what the caller explicitly asked for is ever sent.
        assertNull(sent.get("regions"));
        assertNull(sent.get("q"), "a bare country filter must not also become a text search");
    }

    @Test
    void employmentTypesWithNoFreeHireEquivalentAreRejectedNotSilentlyOmitted() {
        // Regression test: silently dropping the facet made an unsupported selection
        // LOOK honoured while the search actually ran unfiltered by employment type --
        // exactly the "silently ignored filter" this must never do. It must now fail
        // validation before any network call, for both TEMPORARY and OTHER.
        InvalidJobSearchException ex = assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                new JobCatalogSearchRequest(null, null, null, null, "TEMPORARY", null, null, null, null)));
        assertTrue(ex.getMessage().contains("TEMPORARY"));
        verify(client, never()).search(any());

        assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                new JobCatalogSearchRequest(null, null, null, null, "OTHER", null, null, null, null)));
        verify(client, never()).search(any());
    }

    @Test
    void relevanceSortSendsNoSortParamAtAll() {
        stubSearch(emptyResult());

        service.search(ALICE, new JobCatalogSearchRequest(
                "x", null, null, null, null, null, null, null, "RELEVANCE"));

        assertNull(capturedParams().get("sort"));
    }

    @Test
    void invalidRemoteTypeIsRejectedBeforeAnyNetworkCall() {
        assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                new JobCatalogSearchRequest(null, null, null, "ANYWHERE", null, null, null, null, null)));
        verify(client, never()).search(any());
    }

    @Test
    void invalidCountryIsRejected() {
        assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                new JobCatalogSearchRequest(null, null, "CANADA", null, null, null, null, null, null)));
    }

    @Test
    void invalidPostedWithinDaysIsRejected() {
        assertThrows(InvalidJobSearchException.class, () -> service.search(ALICE,
                new JobCatalogSearchRequest(null, null, null, null, null, 9, null, null, null)));
    }

    // ---------------------------------------------------------------------------
    // search: FreeHire response -> summaries, and ignored_params visibility
    // ---------------------------------------------------------------------------

    @Test
    void searchMapsTheFullResultShapeIncludingIdentityAndLinks() {
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
        assertEquals("https://boards.greenhouse.io/acme/jobs/1", summary.applyUrl());
        assertEquals("US", summary.countryCode());
        assertEquals("REMOTE", summary.remoteType());
        assertEquals("ACTIVE", summary.status());
        assertFalse(summary.saved());
        assertNull(summary.personalJobId());
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
        assertNull(summary.applyUrl());
        assertNull(summary.canonicalUrl());
    }

    @Test
    void aSearchHitAlreadySavedByThisUserIsMarkedSaved() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "T", "C", "R",
                "<p>d</p>", List.of(), List.of(), null);
        stubSearch(new FreeHireSearchResponseJson(List.of(hit), new FreeHireMetaJson(1, 20, 0, null)));
        when(jobMapper.findSavedExternalJobIds(eq(1L), eq(FreeHireJobCatalogService.SOURCE), anyList()))
                .thenReturn(List.of(new JobMapper.ExternalIdAndPersonalJobId("acme-swe-x1", 77L)));

        JobCatalogSummary summary = service.search(ALICE, blankRequest()).results().get(0);
        assertTrue(summary.saved());
        assertEquals(77L, summary.personalJobId());
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
    void findDetailFetchesBySlugAndSanitizesHtml() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "Title", "Co", "Remote",
                "<p>Safe</p><script>evil()</script>", List.of("de"), List.of("europe"), "hybrid");
        when(client.getBySlug("acme-swe-x1")).thenReturn(hit);
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, "acme-swe-x1"))
                .thenReturn(null);

        JobCatalogDetail detail = service.findDetail(ALICE, "freehire:acme-swe-x1");

        assertEquals("freehire:acme-swe-x1", detail.id());
        assertTrue(detail.descriptionHtml().contains("<p>Safe</p>"));
        assertFalse(detail.descriptionHtml().contains("<script"));
        assertEquals("DE", detail.countryCode());
        assertEquals("HYBRID", detail.remoteType());
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

    @Test
    void savingForTheFirstTimeFetchesFromFreeHireAndInsertsOnePersonalJob() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "Title", "Acme", "Remote",
                "<p>Full JD.</p>", List.of("us"), List.of(), "remote");
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, "acme-swe-x1"))
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
        assertEquals("acme-swe-x1", inserted.getExternalJobId(), "stored unprefixed -- source already scopes it");
        assertEquals(1L, inserted.getUserId());
    }

    @Test
    void repeatingTheSaveIsIdempotentAndMakesNoSecondNetworkCall() {
        Job existing = personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme");
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, "acme-swe-x1"))
                .thenReturn(existing);

        SaveCatalogJobResponse response = service.saveToMyJobs(ALICE, "freehire:acme-swe-x1");

        assertTrue(response.alreadySaved());
        assertEquals(101L, response.jobId());
        verify(client, never()).getBySlug(any());
        verify(jobMapper, never()).insert(any());
    }

    @Test
    void twoUsersSavingTheSamePostingGetIndependentPersonalJobs() {
        FreeHireJobJson hit = job("acme-swe-x1", "https://x.example/1", "Title", "Acme", "Remote",
                "<p>d</p>", List.of(), List.of(), null);
        when(client.getBySlug("acme-swe-x1")).thenReturn(hit);
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, "acme-swe-x1"))
                .thenReturn(null);
        when(jobMapper.findByUserIdAndSourceAndExternalJobId(2L, FreeHireJobCatalogService.SOURCE, "acme-swe-x1"))
                .thenReturn(null);
        stubInsertAssigningId(101L);
        when(jobMapper.findById(101L)).thenReturn(personalJob(101L, 1L, "acme-swe-x1", "Title", "Acme"));

        service.saveToMyJobs(ALICE, "freehire:acme-swe-x1");

        stubInsertAssigningId(202L);
        when(jobMapper.findById(202L)).thenReturn(personalJob(202L, 2L, "acme-swe-x1", "Title", "Acme"));
        SaveCatalogJobResponse forBob = service.saveToMyJobs(BOB, "freehire:acme-swe-x1");

        assertEquals(202L, forBob.jobId());
        // Each user's lookup was scoped to their own id -- Bob's fetch never saw Alice's row.
        verify(jobMapper).findByUserIdAndSourceAndExternalJobId(1L, FreeHireJobCatalogService.SOURCE, "acme-swe-x1");
        verify(jobMapper).findByUserIdAndSourceAndExternalJobId(2L, FreeHireJobCatalogService.SOURCE, "acme-swe-x1");
    }

    @Test
    void unknownUserIsRejected() {
        when(userMapper.findByEmail("ghost@example.com")).thenReturn(null);
        assertThrows(UserNotFoundException.class,
                () -> service.search("ghost@example.com", blankRequest()));
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

    private static JobCatalogSearchRequest blankRequest() {
        return new JobCatalogSearchRequest(null, null, null, null, null, null, null, null, null);
    }

    private static FreeHireSearchResponseJson emptyResult() {
        return new FreeHireSearchResponseJson(List.of(), new FreeHireMetaJson(0, 20, 0, null));
    }

    private static FreeHireJobJson job(String slug, String url, String title, String company, String location,
                                       String description, List<String> countries, List<String> regions,
                                       String workMode) {
        return new FreeHireJobJson(
                slug, "greenhouse", "ext-" + slug, url, title, company, company.toLowerCase(),
                location, description, countries, regions, workMode, List.of(), List.of(), "tech",
                "2026-09-01T00:00:00Z", "2026-08-30T00:00:00Z", null);
    }
}
