package aijob.service.impl;

import aijob.dto.JobSearchRequest;
import aijob.exception.JobSearchRateLimitedException;
import aijob.exception.JobSearchTimeoutException;
import aijob.exception.JobSearchUnavailableException;
import aijob.jobsearch.JSearchDatePosted;
import aijob.jobsearch.ProviderSearchPage;
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
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class JSearchJobSearchProviderTest {

    private static final String TEST_KEY = "test-jsearch-key-not-real";
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Mock
    private Environment environment;

    private MockRestServiceServer server;
    private JSearchJobSearchProvider provider;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(JSearchJobSearchProvider.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.baseUrl("https://api.openwebninja.com").build();
        provider = new JSearchJobSearchProvider(
                client,
                environment,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void search_usesSearchV2AndXApiKeyWithoutRapidApiHeaders() {
        stubKey();
        expectSearchV2()
                .andExpect(queryParam("query", "java%20in%20Toronto"))
                .andExpect(queryParam("date_posted", "week"))
                .andExpect(requestTo(not(containsString("job-details"))))
                .andRespond(withSuccess("""
                        {
                          "data": [{
                            "job_id": "abc-very-long-raw-id-that-exceeds-sixty-four-characters-xxxxxxxx",
                            "job_title": "Java Developer",
                            "employer_name": "Acme",
                            "job_city": "Toronto",
                            "job_state": "ON",
                            "job_country": "CA",
                            "job_posted_at_datetime_utc": "2026-08-18T12:00:00.000Z",
                            "job_description": "<script>alert(1)</script><p>Build APIs with Java.</p>",
                            "job_apply_link": "https://www.linkedin.com/jobs/view/1",
                            "job_publisher": "LinkedIn",
                            "apply_options": [
                              {
                                "publisher": "LinkedIn",
                                "apply_link": "https://www.linkedin.com/jobs/view/1",
                                "is_direct": false
                              },
                              {
                                "publisher": "Acme",
                                "apply_link": "https://jobs.acme.com/java",
                                "is_direct": true
                              }
                            ]
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProviderSearchPage page = provider.search(new JobSearchRequest("java", "Toronto", 7, 1, 20));

        server.verify();
        assertEquals(1, page.results().size());
        assertNull(page.totalCount());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        var hit = page.results().get(0);
        assertEquals("abc-very-long-raw-id-that-exceeds-sixty-four-characters-xxxxxxxx", hit.rawExternalJobId());
        assertEquals("Java Developer", hit.title());
        assertEquals("Acme", hit.company());
        assertEquals("Toronto, ON, CA", hit.location());
        assertEquals("2026-08-18T12:00:00.000Z", hit.postedAt());
        assertEquals("Build APIs with Java.", hit.fullDescription());
        assertEquals("https://jobs.acme.com/java", hit.sourceUrl());
        assertEquals("LinkedIn", hit.publisher());
        assertEquals("JSEARCH", hit.source());
        assertLogsHideSecretsAndDescription();
    }

    @Test
    void search_cursorPresent_setsHasMoreAndStoresCursor() {
        stubKey();
        expectSearchV2()
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "cursor": "next-page-token",
                            "jobs": [{
                              "job_id": "fresh",
                              "job_title": "Java Developer",
                              "employer_name": "Acme",
                              "job_apply_link": "https://jobs.acme.com/java",
                              "job_description": "Full JD"
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ProviderSearchPage page = provider.search(new JobSearchRequest("java", null, null, 1, 10));
        server.verify();
        assertTrue(page.hasMore());
        assertEquals("next-page-token", page.nextCursor());
        assertNull(page.totalCount());
        assertEquals("fresh", page.results().get(0).rawExternalJobId());
        assertLogsHideSecretsAndDescription();
    }

    @Test
    void search_cursorAbsent_hasMoreFalse() {
        stubKey();
        expectSearchV2()
                .andRespond(withSuccess("""
                        {
                          "data": [{
                            "job_id": "one",
                            "job_title": "Java Developer",
                            "employer_name": "Acme",
                            "job_apply_link": "https://jobs.acme.com/java",
                            "job_description": "Full JD"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProviderSearchPage page = provider.search(new JobSearchRequest("java", null, null, 1, 10));
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
    }

    @Test
    void search_dedupesSameApplyUrlFromDifferentPublishers() {
        stubKey();
        expectSearchV2()
                .andRespond(withSuccess("""
                        {
                          "data": [
                            {
                              "job_id": "one",
                              "job_title": "Java Developer",
                              "employer_name": "Acme",
                              "job_apply_link": "https://jobs.acme.com/java?utm=linkedin",
                              "job_publisher": "LinkedIn",
                              "job_description": "Full JD one"
                            },
                            {
                              "job_id": "two",
                              "job_title": "Java Developer",
                              "employer_name": "Acme",
                              "job_apply_link": "https://jobs.acme.com/java",
                              "job_publisher": "Indeed",
                              "job_description": "Full JD two"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProviderSearchPage page = provider.search(new JobSearchRequest("java", null, null, 1, 10));
        assertEquals(1, page.results().size());
        assertEquals("one", page.results().get(0).rawExternalJobId());
    }

    @Test
    void search_filtersPostedAtForFourteenDaysAfterMonthQuery() {
        stubKey();
        expectSearchV2()
                .andExpect(queryParam("date_posted", "month"))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            {
                              "job_id": "old",
                              "job_title": "Old Role",
                              "employer_name": "Acme",
                              "job_apply_link": "https://jobs.acme.com/old",
                              "job_posted_at_datetime_utc": "2026-07-01T00:00:00Z",
                              "job_description": "Old"
                            },
                            {
                              "job_id": "fresh",
                              "job_title": "Fresh Role",
                              "employer_name": "Acme",
                              "job_apply_link": "https://jobs.acme.com/fresh",
                              "job_posted_at_datetime_utc": "2026-08-15T00:00:00Z",
                              "job_description": "Fresh"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProviderSearchPage page = provider.search(new JobSearchRequest("java", null, 14, 1, 10));
        assertEquals(1, page.results().size());
        assertEquals("fresh", page.results().get(0).rawExternalJobId());
    }

    @Test
    void datePosted_mapsConfirmedWindows() {
        assertEquals("all", JSearchDatePosted.queryValue(null));
        assertEquals("today", JSearchDatePosted.queryValue(1));
        assertEquals("3days", JSearchDatePosted.queryValue(3));
        assertEquals("week", JSearchDatePosted.queryValue(7));
        assertEquals("month", JSearchDatePosted.queryValue(14));
        assertEquals("month", JSearchDatePosted.queryValue(30));
    }

    @Test
    void search_requestsSinglePageEvenWhenPageSizeIsTwenty() {
        stubKey();
        expectSearchV2()
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        provider.search(new JobSearchRequest("java", null, null, 1, 20));
        server.verify();
    }

    @Test
    void search_missingKey_returns502WithoutHttp() {
        when(environment.getProperty("JSEARCH_API_KEY")).thenReturn(" ");
        JobSearchUnavailableException ex = assertThrows(
                JobSearchUnavailableException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));
        assertEquals(JobSearchUnavailableException.REASON_CONFIG_MISSING, ex.getReasonCode());
        server.verify();
        assertTrue(logs().contains("reasonCode=CONFIG_MISSING"));
        assertLogsHideSecretsAndDescription();
    }

    @Test
    void search_upstream401() {
        stubKey();
        expectSearchV2().andRespond(withStatus(UNAUTHORIZED));
        JobSearchUnavailableException ex = assertThrows(
                JobSearchUnavailableException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));
        assertEquals(401, ex.getUpstreamStatus());
        assertLogsHideSecretsAndDescription();
    }

    @Test
    void search_upstream403() {
        stubKey();
        expectSearchV2().andRespond(withStatus(FORBIDDEN));
        JobSearchUnavailableException ex = assertThrows(
                JobSearchUnavailableException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));
        assertEquals(403, ex.getUpstreamStatus());
    }

    @Test
    void search_upstream429() {
        stubKey();
        expectSearchV2().andRespond(withStatus(TOO_MANY_REQUESTS));
        assertThrows(JobSearchRateLimitedException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));
    }

    @Test
    void search_upstream5xx() {
        stubKey();
        expectSearchV2().andRespond(withServerError());
        JobSearchUnavailableException ex = assertThrows(
                JobSearchUnavailableException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));
        assertEquals(500, ex.getUpstreamStatus());
    }

    @Test
    void search_withCursor_sendsCursorAndHidesItFromLogs() {
        stubKey();
        server.expect(once(), requestTo(containsString("/jsearch/search-v2")))
                .andExpect(method(GET))
                .andExpect(header("x-api-key", TEST_KEY))
                .andExpect(queryParam("query", "java"))
                .andExpect(queryParam("cursor", "raw-next-page-cursor-token"))
                .andExpect(queryParam("date_posted", "all"))
                .andExpect(noRapidApiHeaders())
                .andRespond(withSuccess("""
                        {
                          "data": [{
                            "job_id": "page-2",
                            "job_title": "Java Developer",
                            "employer_name": "Acme",
                            "job_apply_link": "https://jobs.acme.com/java-2",
                            "job_description": "Full JD page 2"
                          }],
                          "cursor": "another-cursor"
                        }
                        """, MediaType.APPLICATION_JSON));

        ProviderSearchPage page = provider.search(
                new JobSearchRequest("java", null, null, 1, 10),
                "raw-next-page-cursor-token");
        server.verify();
        assertEquals(1, page.results().size());
        assertEquals("page-2", page.results().get(0).rawExternalJobId());
        assertEquals("another-cursor", page.nextCursor());
        assertLogsHideSecretsAndDescription();
        assertFalse(logs().contains("raw-next-page-cursor-token"), logs());
        assertFalse(logs().contains("another-cursor"), logs());
    }

    @Test
    void search_timeout() {
        stubKey();
        expectSearchV2()
                .andRespond(request -> {
                    throw new ResourceAccessException("Read timed out");
                });
        assertThrows(JobSearchTimeoutException.class,
                () -> provider.search(new JobSearchRequest("java", null, null, 1, 10)));
        assertTrue(logs().contains("NETWORK_OR_TIMEOUT"));
        assertLogsHideSecretsAndDescription();
    }

    private ResponseActions expectSearchV2() {
        return server.expect(once(), requestTo(containsString("/jsearch/search-v2")))
                .andExpect(method(GET))
                .andExpect(header("x-api-key", TEST_KEY))
                .andExpect(queryParam("country", "ca"))
                .andExpect(queryParam("language", "en"))
                .andExpect(queryParam("num_pages", "1"))
                .andExpect(requestTo(not(containsString("cursor="))))
                .andExpect(noRapidApiHeaders());
    }

    private static RequestMatcher noRapidApiHeaders() {
        return request -> {
            for (String name : request.getHeaders().keySet()) {
                assertFalse(name.toLowerCase(Locale.ROOT).startsWith("x-rapidapi"), name);
            }
        };
    }

    private void stubKey() {
        when(environment.getProperty("JSEARCH_API_KEY")).thenReturn(TEST_KEY);
    }

    private String logs() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    private void assertLogsHideSecretsAndDescription() {
        String text = logs();
        assertFalse(text.contains(TEST_KEY), text);
        assertFalse(text.contains("x-api-key"), text);
        assertFalse(text.contains("X-RapidAPI-Key"), text);
        assertFalse(text.contains("X-RapidAPI-Host"), text);
        assertFalse(text.contains("Build APIs with Java."), text);
        assertFalse(text.contains("query=java"), text);
    }
}
