package aijob.collector;

import aijob.entity.JobSource;
import aijob.enums.AtsProviderType;
import aijob.enums.EmploymentType;
import aijob.enums.RemoteType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Normalization from a recorded Lever postings payload. No live board is contacted.
 */
class LeverBoardAdapterTest {

    private static final String FIRST_PAGE =
            JobCollectorClientConfig.LEVER_BASE_URL + "/acme?mode=json&limit=100&skip=0";

    private MockRestServiceServer server;
    private JobCollectorProperties properties;
    private LeverBoardAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new JobCollectorProperties();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.baseUrl(JobCollectorClientConfig.LEVER_BASE_URL).build();
        adapter = new LeverBoardAdapter(client, properties,
                new CollectorRetry(2, 0, 0, new Random(1), millis -> { }));
    }

    @Test
    void normalizesAPublicPostingsPayload() throws IOException {
        stubPostings();

        List<NormalizedJob> jobs = adapter.fetch(source("acme"));
        server.verify();

        // The posting with no body at all is dropped.
        assertEquals(2, jobs.size());

        NormalizedJob backend = jobs.get(0);
        assertEquals(AtsProviderType.LEVER.name(), backend.provider());
        assertEquals("6f2a1c40-0000-4000-8000-000000000001", backend.externalJobId());
        assertEquals("Backend Engineer", backend.title());
        assertEquals("Acme", backend.company());
        assertEquals("Toronto, ON | Montreal, QC", backend.location());
        assertEquals("CA", backend.country());
        assertEquals(RemoteType.HYBRID, backend.remoteType());
        assertEquals(EmploymentType.FULL_TIME, backend.employmentType());
        assertEquals("https://jobs.lever.co/acme/6f2a1c40-0000-4000-8000-000000000001",
                backend.canonicalUrl());
        assertEquals("https://jobs.lever.co/acme/6f2a1c40-0000-4000-8000-000000000001/apply",
                backend.applyUrl());
    }

    @Test
    void readsCreatedAtAsEpochMilliseconds() throws IOException {
        stubPostings();

        List<NormalizedJob> jobs = adapter.fetch(source("acme"));

        // createdAt 1785312000000 is 2026-07-29T08:00Z read as milliseconds; read as
        // seconds the same number lands tens of thousands of years out.
        assertEquals(LocalDateTime.of(2026, 7, 29, 8, 0), jobs.get(0).postedAt());
        assertNull(jobs.get(1).postedAt());
    }

    @Test
    void keepsTheRequirementsThatLeverStoresOutsideTheIntro() throws IOException {
        stubPostings();

        NormalizedJob backend = adapter.fetch(source("acme")).get(0);

        assertTrue(backend.descriptionText().contains("Join the backend team."));
        assertTrue(backend.descriptionText().contains("Requirements"));
        assertTrue(backend.descriptionText().contains("Kotlin or Java"));
        assertTrue(backend.descriptionText().contains("equal opportunity"));
        assertFalse(backend.descriptionHtml().contains("<script"));
    }

    @Test
    void readsTheSalaryRangeAndUppercasesTheCurrency() throws IOException {
        stubPostings();

        List<NormalizedJob> jobs = adapter.fetch(source("acme"));
        NormalizedJob backend = jobs.get(0);

        assertNotNull(backend.salary());
        assertEquals(0, new BigDecimal("120000").compareTo(backend.salary().min()));
        assertEquals(0, new BigDecimal("160000").compareTo(backend.salary().max()));
        assertEquals("CAD", backend.salary().currency());
        assertNull(jobs.get(1).salary());
    }

    @Test
    void infersRemoteFromTheLocationWhenTheBoardDoesNotSayIt() throws IOException {
        stubPostings();

        NormalizedJob support = adapter.fetch(source("acme")).get(1);

        assertEquals(RemoteType.REMOTE, support.remoteType());
        assertEquals(EmploymentType.CONTRACT, support.employmentType());
    }

    @Test
    void refusesATenantKeyThatIsNotASiteSlug() {
        JobCollectionException error = assertThrows(JobCollectionException.class,
                () -> adapter.fetch(source("acme/../admin")));

        assertEquals("INVALID_TENANT_KEY", error.reason());
        server.verify();
    }

    @Test
    void anUnexpectedShapeIsReportedRatherThanPartiallyIngested() {
        server.expect(requestTo(FIRST_PAGE))
                .andRespond(withSuccess("{\"postings\":[]}", MediaType.APPLICATION_JSON));

        JobCollectionException error =
                assertThrows(JobCollectionException.class, () -> adapter.fetch(source("acme")));

        assertEquals("SOURCE_PAYLOAD_UNEXPECTED", error.reason());
    }

    @Test
    void stopsAfterAShortPageInsteadOfPagingForever() throws IOException {
        // A single page shorter than the page limit means the board is exhausted.
        stubPostings();

        adapter.fetch(source("acme"));

        server.verify();
    }

    private void stubPostings() throws IOException {
        server.expect(requestTo(FIRST_PAGE))
                .andExpect(method(GET))
                .andRespond(withSuccess(fixture("lever-postings.json"), MediaType.APPLICATION_JSON));
    }

    private static JobSource source(String tenantKey) {
        JobSource source = new JobSource();
        source.setId(2L);
        source.setProviderType(AtsProviderType.LEVER.name());
        source.setDisplayName("Acme");
        source.setTenantKey(tenantKey);
        source.setEnabled(true);
        source.setCrawlAllowed(true);
        return source;
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("collector/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
