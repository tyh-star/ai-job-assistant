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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Normalization from a recorded Greenhouse board payload. No live board is contacted.
 */
class GreenhouseBoardAdapterTest {

    private MockRestServiceServer server;
    private JobCollectorProperties properties;
    private GreenhouseBoardAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new JobCollectorProperties();
        properties.setContactEmail("ops@example.com");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder
                .baseUrl(JobCollectorClientConfig.GREENHOUSE_BASE_URL)
                .defaultHeader("User-Agent", JobCollectorClientConfig.userAgent(properties))
                .build();
        adapter = new GreenhouseBoardAdapter(client, properties,
                new CollectorRetry(2, 0, 0, new Random(1), millis -> { }));
    }

    @Test
    void normalizesAPublicBoardPayload() throws IOException {
        stubBoard();

        List<NormalizedJob> jobs = adapter.fetch(source("acme"));
        server.verify();

        // The posting with no body is dropped: Match and Tailor need a description.
        assertEquals(2, jobs.size());

        NormalizedJob java = jobs.get(0);
        assertEquals(AtsProviderType.GREENHOUSE.name(), java.provider());
        assertEquals("acme", java.tenant());
        assertEquals("4001", java.externalJobId());
        assertEquals("Java Developer", java.title());
        assertEquals("Acme Inc", java.company());
        assertEquals("Toronto, ON, Canada", java.location());
        assertEquals("CA", java.country());
        assertEquals(EmploymentType.UNSPECIFIED, java.employmentType());
        assertEquals("https://boards.greenhouse.io/acme/jobs/4001", java.canonicalUrl());
        assertEquals("https://boards.greenhouse.io/acme/jobs/4001", java.applyUrl());
        assertEquals(LocalDateTime.of(2026, 8, 1, 13, 30), java.postedAt());
        assertTrue(java.usable());

        NormalizedJob remote = jobs.get(1);
        assertEquals(RemoteType.REMOTE, remote.remoteType());
        // "Remote - United States". This asserted null until the location rules learned
        // to look past a work-arrangement prefix -- the old expectation was recording a
        // parser limitation as if it were intended behaviour. The posting plainly names
        // a country, and the remote arrangement does not make it stateless.
        assertEquals("US", remote.country());
    }

    @Test
    void stripsScriptFromTheDescriptionAndKeepsThePlainText() throws IOException {
        stubBoard();

        NormalizedJob java = adapter.fetch(source("acme")).get(0);

        assertTrue(java.descriptionText().contains("Build APIs with Java."));
        assertTrue(java.descriptionText().contains("5 years Java"));
        assertFalse(java.descriptionHtml().contains("<script"));
        assertFalse(java.descriptionHtml().contains("steal()"));
        assertTrue(java.descriptionHtml().contains("<p>Build APIs with Java.</p>"));
    }

    @Test
    void identifiesItselfWithAContactAddress() throws IOException {
        server.expect(requestTo(JobCollectorClientConfig.GREENHOUSE_BASE_URL + "/acme"))
                .andExpect(method(GET))
                .andExpect(header("User-Agent", "AiJobAssistantCollector/1.0 (+mailto:ops@example.com)"))
                .andRespond(withSuccess(fixture("greenhouse-board.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(JobCollectorClientConfig.GREENHOUSE_BASE_URL + "/acme/jobs?content=true"))
                .andExpect(method(GET))
                .andRespond(withSuccess(fixture("greenhouse-jobs.json"), MediaType.APPLICATION_JSON));

        adapter.fetch(source("acme"));
        server.verify();
    }

    @Test
    void respectsThePerSourceCap() throws IOException {
        properties.setMaxJobsPerSource(1);
        stubBoard();

        assertEquals(1, adapter.fetch(source("acme")).size());
    }

    @Test
    void truncatesAnOversizedDescriptionAndStoredPayload() throws IOException {
        properties.setMaxDescriptionChars(10);
        properties.setMaxRawPayloadChars(20);
        stubBoard();

        NormalizedJob java = adapter.fetch(source("acme")).get(0);

        assertTrue(java.descriptionText().length() <= 10);
        assertTrue(java.rawPayload().length() <= 20);
    }

    @Test
    void refusesATenantKeyThatIsNotABoardToken() {
        JobCollectionException error = assertThrows(JobCollectionException.class,
                () -> adapter.fetch(source("../../etc/passwd")));

        assertEquals("INVALID_TENANT_KEY", error.reason());
        server.verify();
    }

    @Test
    void anUnknownBoardIsReportedWithoutRetrying() throws IOException {
        server.expect(requestTo(JobCollectorClientConfig.GREENHOUSE_BASE_URL + "/acme"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        JobCollectionException error =
                assertThrows(JobCollectionException.class, () -> adapter.fetch(source("acme")));

        assertEquals("SOURCE_NOT_FOUND", error.reason());
        server.verify();
    }

    @Test
    void anUnexpectedShapeIsReportedRatherThanPartiallyIngested() throws IOException {
        server.expect(requestTo(JobCollectorClientConfig.GREENHOUSE_BASE_URL + "/acme"))
                .andRespond(withSuccess(fixture("greenhouse-board.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(JobCollectorClientConfig.GREENHOUSE_BASE_URL + "/acme/jobs?content=true"))
                .andRespond(withSuccess("{\"unexpected\":true}", MediaType.APPLICATION_JSON));

        JobCollectionException error =
                assertThrows(JobCollectionException.class, () -> adapter.fetch(source("acme")));

        assertEquals("SOURCE_PAYLOAD_UNEXPECTED", error.reason());
    }

    private void stubBoard() throws IOException {
        server.expect(requestTo(JobCollectorClientConfig.GREENHOUSE_BASE_URL + "/acme"))
                .andExpect(method(GET))
                .andRespond(withSuccess(fixture("greenhouse-board.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(JobCollectorClientConfig.GREENHOUSE_BASE_URL + "/acme/jobs?content=true"))
                .andExpect(method(GET))
                .andRespond(withSuccess(fixture("greenhouse-jobs.json"), MediaType.APPLICATION_JSON));
    }

    private static JobSource source(String tenantKey) {
        JobSource source = new JobSource();
        source.setId(1L);
        source.setProviderType(AtsProviderType.GREENHOUSE.name());
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
