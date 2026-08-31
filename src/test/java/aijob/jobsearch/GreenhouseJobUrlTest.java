package aijob.jobsearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreenhouseJobUrlTest {

    @Test
    void parsesOfficialBoardHosts() {
        var parsed = GreenhouseJobUrl.parseOfficialJobUrl("https://boards.greenhouse.io/airbnb/jobs/123");
        assertTrue(parsed.isPresent());
        assertEquals("airbnb", parsed.get().boardToken());
        assertEquals("123", parsed.get().jobId());
        assertTrue(GreenhouseJobUrl.parseOfficialJobUrl(
                "https://job-boards.greenhouse.io/acme/jobs/99").isPresent());
    }

    @Test
    void rejectsUnknownHostsAndNonHttpsSchemes() {
        assertTrue(GreenhouseJobUrl.parseOfficialJobUrl("https://evil.example/airbnb/jobs/123").isEmpty());
        assertTrue(GreenhouseJobUrl.parseOfficialJobUrl("https://boards.greenhouse.io.evil.example/airbnb/jobs/123").isEmpty());
        assertTrue(GreenhouseJobUrl.parseOfficialJobUrl("file:///etc/passwd").isEmpty());
        assertTrue(GreenhouseJobUrl.parseOfficialJobUrl("https://127.0.0.1/airbnb/jobs/123").isEmpty());
        assertTrue(GreenhouseJobUrl.parseOfficialJobUrl("https://boards.greenhouse.io/airbnb").isEmpty());
    }
}
