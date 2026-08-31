package aijob.service.impl;

import aijob.dto.JobSearchRequest;
import aijob.jobsearch.ProviderSearchPage;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobSearchRateLimitedException;
import aijob.exception.JobSearchUnavailableException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@ExtendWith(MockitoExtension.class)
class AdzunaJobSearchProviderTest {

    private static final String TEST_APP_ID = "test-app-id";
    private static final String TEST_APP_KEY = "test-app-key";

    @Mock
    private Environment environment;

    private MockRestServiceServer server;
    private AdzunaJobSearchProvider provider;
    private Logger adzunaLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        adzunaLogger = (Logger) LoggerFactory.getLogger(AdzunaJobSearchProvider.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        adzunaLogger.addAppender(logAppender);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.baseUrl("https://api.adzuna.com").build();
        provider = new AdzunaJobSearchProvider(client, environment);
    }

    @AfterEach
    void tearDown() {
        adzunaLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void search_usesCanadaCountryPathAndMapsRedirectUrl() {
        stubSecrets("ca");
        server.expect(once(), requestTo(containsString("/v1/api/jobs/ca/search/1")))
                .andExpect(method(GET))
                .andExpect(queryParam("what", "java"))
                .andExpect(queryParam("where", "Toronto"))
                .andExpect(queryParam("max_days_old", "7"))
                .andExpect(requestTo(not(containsString("where=Remote"))))
                .andRespond(withSuccess("""
                        {
                          "count": 1,
                          "results": [{
                            "id": 123,
                            "title": "Java Developer",
                            "description": "<p>Build APIs</p>",
                            "created": "2026-08-01T12:00:00Z",
                            "redirect_url": "https://www.adzuna.ca/land/ad/123",
                            "location": { "display_name": "Toronto, Ontario" },
                            "company": { "display_name": "Acme" }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProviderSearchPage response = provider.search(
                new JobSearchRequest("java", "Toronto", 7, 1, 10));

        server.verify();
        assertEquals(1, response.results().size());
        assertEquals("123", response.results().get(0).rawExternalJobId());
        assertEquals("Java Developer", response.results().get(0).title());
        assertEquals("Acme", response.results().get(0).company());
        assertEquals("Toronto, Ontario", response.results().get(0).location());
        assertEquals("Build APIs", response.results().get(0).fullDescription());
        assertEquals("ADZUNA", response.results().get(0).source());
        assertEquals("https://www.adzuna.ca/land/ad/123", response.results().get(0).sourceUrl());
        assertLogsHideSecrets();
        assertTrue(logs().contains("Adzuna configuration:"));
        assertTrue(logs().contains("appIdPresent=true"));
        assertTrue(logs().contains("country=ca"));
    }

    @Test
    void search_defaultsCountryToCaWhenEnvMissing() {
        when(environment.getProperty("ADZUNA_APP_ID")).thenReturn("test-app-id");
        when(environment.getProperty("ADZUNA_APP_KEY")).thenReturn("test-app-key");
        when(environment.getProperty("ADZUNA_COUNTRY_CODE")).thenReturn(null);
        server.expect(once(), requestTo(containsString("/v1/api/jobs/ca/search/1")))
                .andExpect(requestTo(not(containsString("/jobs/us/"))))
                .andExpect(requestTo(not(containsString("where=Remote"))))
                .andRespond(withSuccess("{\"count\":0,\"results\":[]}", MediaType.APPLICATION_JSON));

        ProviderSearchPage response = provider.search(new JobSearchRequest("java", null, null, 1, 10));

        server.verify();
        assertEquals(0L, response.totalCount());
        assertTrue(response.results().isEmpty());
    }

    @Test
    void search_doesNotSendWhereRemoteOrMaxDaysWhenUnlimited() {
        stubSecrets("ca");
        server.expect(once(), requestTo(containsString("/v1/api/jobs/ca/search/1")))
                .andExpect(requestTo(not(containsString("where="))))
                .andExpect(requestTo(not(containsString("max_days_old"))))
                .andExpect(requestTo(not(containsString("Remote"))))
                .andRespond(withSuccess("{\"count\":0,\"results\":[]}", MediaType.APPLICATION_JSON));

        provider.search(new JobSearchRequest("java", "  ", 0, 1, 10));
        server.verify();
    }

    @Test
    void search_blankKeywords_returns400WithoutHttp() {
        assertThrows(InvalidJobSearchException.class,
                () -> provider.search(new JobSearchRequest("  ", null, null, 1, 10)));
        server.verify();
    }

    @Test
    void search_missingAppId_configMissingWithoutHttp() {
        when(environment.getProperty("ADZUNA_APP_ID")).thenReturn(null);
        when(environment.getProperty("ADZUNA_APP_KEY")).thenReturn(TEST_APP_KEY);

        JobSearchUnavailableException ex = assertThrows(
                JobSearchUnavailableException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));

        assertEquals("Job search is temporarily unavailable.", ex.getMessage());
        assertEquals(JobSearchUnavailableException.REASON_CONFIG_MISSING, ex.getReasonCode());
        assertEquals(null, ex.getUpstreamStatus());
        server.verify();
        String text = logs();
        assertTrue(text.contains("reasonCode=CONFIG_MISSING"), text);
        assertTrue(text.contains("missingFields=[ADZUNA_APP_ID]"), text);
        assertTrue(text.contains("appIdPresent=false"), text);
        assertLogsHideSecrets();
    }

    @Test
    void search_missingAppKey_configMissingWithoutHttp() {
        when(environment.getProperty("ADZUNA_APP_ID")).thenReturn(TEST_APP_ID);
        when(environment.getProperty("ADZUNA_APP_KEY")).thenReturn(" ");

        JobSearchUnavailableException ex = assertThrows(
                JobSearchUnavailableException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));

        assertEquals("Job search is temporarily unavailable.", ex.getMessage());
        assertEquals(JobSearchUnavailableException.REASON_CONFIG_MISSING, ex.getReasonCode());
        server.verify();
        String text = logs();
        assertTrue(text.contains("reasonCode=CONFIG_MISSING"), text);
        assertTrue(text.contains("missingFields=[ADZUNA_APP_KEY]"), text);
        assertTrue(text.contains("appKeyPresent=false"), text);
        assertLogsHideSecrets();
    }

    @Test
    void search_upstream401_logsUpstreamStatus() {
        stubSecrets("ca");
        server.expect(once(), requestTo(containsString("/v1/api/jobs/ca/search/1")))
                .andRespond(withStatus(UNAUTHORIZED));

        JobSearchUnavailableException ex = assertThrows(
                JobSearchUnavailableException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));

        assertEquals("Job search is temporarily unavailable.", ex.getMessage());
        assertEquals(JobSearchUnavailableException.REASON_UPSTREAM_STATUS, ex.getReasonCode());
        assertEquals(401, ex.getUpstreamStatus());
        server.verify();
        String text = logs();
        assertTrue(text.contains("reasonCode=UPSTREAM_STATUS"), text);
        assertTrue(text.contains("upstreamStatus=401"), text);
        assertTrue(text.contains("provider=ADZUNA"), text);
        assertTrue(text.contains("country=ca"), text);
        assertLogsHideSecrets();
    }

    @Test
    void search_upstream403_logsUpstreamStatus() {
        stubSecrets("ca");
        server.expect(once(), requestTo(containsString("/v1/api/jobs/ca/search/1")))
                .andRespond(withStatus(FORBIDDEN));

        JobSearchUnavailableException ex = assertThrows(
                JobSearchUnavailableException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));

        assertEquals("Job search is temporarily unavailable.", ex.getMessage());
        assertEquals(JobSearchUnavailableException.REASON_UPSTREAM_STATUS, ex.getReasonCode());
        assertEquals(403, ex.getUpstreamStatus());
        server.verify();
        String text = logs();
        assertTrue(text.contains("reasonCode=UPSTREAM_STATUS"), text);
        assertTrue(text.contains("upstreamStatus=403"), text);
        assertLogsHideSecrets();
    }

    @Test
    void search_rateLimit_mapsTo429() {
        stubSecrets("ca");
        server.expect(once(), requestTo(containsString("/v1/api/jobs/ca/search/1")))
                .andRespond(withStatus(TOO_MANY_REQUESTS));
        assertThrows(JobSearchRateLimitedException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));
        server.verify();
    }

    @Test
    void search_upstream5xx_mapsToUnavailable() {
        stubSecrets("ca");
        server.expect(once(), requestTo(containsString("/v1/api/jobs/ca/search/1")))
                .andRespond(withServerError());
        assertThrows(JobSearchUnavailableException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));
        server.verify();
    }

    @Test
    void countryCode_defaultsToCaForInvalidValue() {
        when(environment.getProperty("ADZUNA_COUNTRY_CODE")).thenReturn("canada");
        assertEquals("ca", provider.countryCode());
    }

    private void stubSecrets(String country) {
        when(environment.getProperty("ADZUNA_APP_ID")).thenReturn(TEST_APP_ID);
        when(environment.getProperty("ADZUNA_APP_KEY")).thenReturn(TEST_APP_KEY);
        when(environment.getProperty("ADZUNA_COUNTRY_CODE")).thenReturn(country);
    }

    private String logs() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    private void assertLogsHideSecrets() {
        String text = logs();
        assertFalse(text.contains(TEST_APP_ID), text);
        assertFalse(text.contains(TEST_APP_KEY), text);
        assertFalse(text.contains("app_id="), text);
        assertFalse(text.contains("app_key="), text);
    }
}
