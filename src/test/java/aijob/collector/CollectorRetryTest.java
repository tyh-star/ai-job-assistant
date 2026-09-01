package aijob.collector;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectorRetryTest {

    private final List<Long> sleeps = new ArrayList<>();

    /** Deterministic "random" so backoff growth can be asserted exactly. */
    private final Random noJitter = new Random() {
        @Override
        public int nextInt(int bound) {
            return 0;
        }
    };

    private CollectorRetry retry(int attempts) {
        return new CollectorRetry(attempts, 100, 1_000, noJitter, sleeps::add);
    }

    @Test
    void aSuccessfulCallDoesNotSleep() {
        assertEquals("ok", retry(3).call("test", () -> "ok"));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void aTimeoutIsRetriedWithGrowingBackoff() {
        AtomicInteger attempts = new AtomicInteger();
        String result = retry(3).call("test", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ResourceAccessException("timeout", new SocketTimeoutException("read timed out"));
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
        // base 100ms then 200ms, halved by the jitter window with nextInt pinned to 0.
        assertEquals(List.of(50L, 100L), sleeps);
    }

    @Test
    void backoffIsCappedAtTheConfiguredMaximum() {
        CollectorRetry capped = new CollectorRetry(6, 500, 1_000, noJitter, sleeps::add);

        assertThrows(JobCollectionException.class,
                () -> capped.call("test", () -> {
                    throw new ResourceAccessException("timeout");
                }));

        assertEquals(5, sleeps.size());
        assertTrue(sleeps.stream().allMatch(delay -> delay <= 1_000L));
    }

    @Test
    void jitterKeepsRetriesFromLiningUp() {
        List<Long> jittered = new ArrayList<>();
        CollectorRetry random = new CollectorRetry(4, 1_000, 8_000, new Random(42), jittered::add);

        assertThrows(JobCollectionException.class,
                () -> random.call("test", () -> {
                    throw new ResourceAccessException("timeout");
                }));

        assertEquals(3, jittered.size());
        assertTrue(jittered.stream().distinct().count() > 1,
                "expected jitter to vary the delays, got " + jittered);
    }

    @Test
    void exhaustedRetriesReportAClassifiedReason() {
        JobCollectionException error = assertThrows(JobCollectionException.class,
                () -> retry(2).call("test", () -> {
                    throw new ResourceAccessException("timeout");
                }));

        assertEquals("NETWORK_OR_TIMEOUT", error.reason());
    }

    @Test
    void aServerErrorIsRetriedAndARateLimitIsToo() {
        AtomicInteger serverErrors = new AtomicInteger();
        assertThrows(JobCollectionException.class,
                () -> retry(2).call("test", () -> {
                    serverErrors.incrementAndGet();
                    throw response(HttpStatus.SERVICE_UNAVAILABLE);
                }));
        assertEquals(2, serverErrors.get());

        AtomicInteger rateLimits = new AtomicInteger();
        JobCollectionException limited = assertThrows(JobCollectionException.class,
                () -> retry(2).call("test", () -> {
                    rateLimits.incrementAndGet();
                    throw response(HttpStatus.TOO_MANY_REQUESTS);
                }));
        assertEquals(2, rateLimits.get());
        assertEquals("SOURCE_RATE_LIMITED", limited.reason());
    }

    @Test
    void aClientErrorIsAnAnswerAndIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        JobCollectionException error = assertThrows(JobCollectionException.class,
                () -> retry(3).call("test", () -> {
                    calls.incrementAndGet();
                    throw response(HttpStatus.FORBIDDEN);
                }));

        assertEquals(1, calls.get());
        assertEquals("SOURCE_REJECTED_403", error.reason());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void aMissingBoardIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        JobCollectionException error = assertThrows(JobCollectionException.class,
                () -> retry(3).call("test", () -> {
                    calls.incrementAndGet();
                    throw response(HttpStatus.NOT_FOUND);
                }));

        assertEquals(1, calls.get());
        assertEquals("SOURCE_NOT_FOUND", error.reason());
    }

    @Test
    void theClassifiedReasonNeverCarriesTheResponseBody() {
        JobCollectionException error = assertThrows(JobCollectionException.class,
                () -> retry(1).call("test", () -> {
                    throw new RestClientResponseException(
                            "boom", HttpStatus.FORBIDDEN.value(), "Forbidden", null,
                            "{\"apiKey\":\"super-secret-value\"}".getBytes(), null);
                }));

        assertEquals("SOURCE_REJECTED_403", error.reason());
        assertEquals("SOURCE_REJECTED_403", error.getMessage());
    }

    private static RestClientResponseException response(HttpStatus status) {
        return new RestClientResponseException(
                status.name(), status.value(), status.getReasonPhrase(), null, null, null);
    }
}
