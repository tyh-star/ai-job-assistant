package aijob.client;

import aijob.exception.GreenhouseBoardNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GreenhouseJobClientFetchJobTest {

    private MockRestServiceServer server;
    private GreenhouseJobClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("https://boards-api.greenhouse.io/v1/boards").build();
        client = new GreenhouseJobClientImpl(restClient);
    }

    @Test
    void fetchJob_usesOfficialApiAndDoesNotFetchBoard() {
        server.expect(once(), requestTo("https://boards-api.greenhouse.io/v1/boards/airbnb/jobs/123"))
                .andExpect(method(GET))
                .andExpect(requestTo(containsString("/jobs/123")))
                .andRespond(withSuccess("""
                        {
                          "id": 123,
                          "title": "Java Developer",
                          "absolute_url": "https://boards.greenhouse.io/airbnb/jobs/123",
                          "location": { "name": "Toronto" },
                          "content": "<p>Official Greenhouse description</p>"
                        }
                        """, MediaType.APPLICATION_JSON));

        GreenhouseJobClient.GreenhouseJobPosting posting = client.fetchJob("airbnb", "123");
        server.verify();
        assertEquals("123", posting.externalJobId());
        assertEquals("<p>Official Greenhouse description</p>", posting.htmlContent());
    }

    @Test
    void fetchJob_404() {
        server.expect(once(), requestTo(containsString("/airbnb/jobs/123")))
                .andRespond(withResourceNotFound());
        assertThrows(GreenhouseBoardNotFoundException.class, () -> client.fetchJob("airbnb", "123"));
    }
}
