package aijob.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Random;
import java.util.function.Supplier;

/**
 * Exponential backoff with jitter for board reads.
 *
 * <p>Jitter matters because several sources are collected on the same tick: without
 * it, a shared outage would line every retry up on the same instant and hammer the
 * board. 4xx responses other than 429 are not retried; the board has answered.
 */
public final class CollectorRetry {

    private static final Logger log = LoggerFactory.getLogger(CollectorRetry.class);

    private final int maxAttempts;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final Random random;
    private final Sleeper sleeper;

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public CollectorRetry(JobCollectorProperties properties) {
        this(properties.getMaxRetries(),
                properties.getRetryBaseDelayMillis(),
                properties.getRetryMaxDelayMillis(),
                new Random(),
                Thread::sleep);
    }

    CollectorRetry(int maxAttempts,
                   long baseDelayMillis,
                   long maxDelayMillis,
                   Random random,
                   Sleeper sleeper) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMillis = Math.max(0, baseDelayMillis);
        this.maxDelayMillis = Math.max(0, maxDelayMillis);
        this.random = random;
        this.sleeper = sleeper;
    }

    public <T> T call(String label, Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (ResourceAccessException ex) {
                last = new JobCollectionException("NETWORK_OR_TIMEOUT", ex);
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 404) {
                    throw new JobCollectionException("SOURCE_NOT_FOUND", ex);
                }
                if (status != 429 && status < 500) {
                    throw new JobCollectionException("SOURCE_REJECTED_" + status, ex);
                }
                last = new JobCollectionException(
                        status == 429 ? "SOURCE_RATE_LIMITED" : "SOURCE_UNAVAILABLE_" + status, ex);
            }
            if (attempt < maxAttempts) {
                sleepBeforeRetry(label, attempt);
            }
        }
        throw last == null ? new JobCollectionException("SOURCE_UNAVAILABLE") : last;
    }

    private void sleepBeforeRetry(String label, int attempt) {
        long exponential = baseDelayMillis * (1L << (attempt - 1));
        long capped = Math.min(exponential, maxDelayMillis);
        long jittered = capped <= 0 ? 0 : capped / 2 + random.nextInt((int) Math.max(1, capped / 2 + 1));
        log.debug("Retrying {} attempt {} in {}ms", label, attempt + 1, jittered);
        try {
            sleeper.sleep(jittered);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new JobCollectionException("INTERRUPTED");
        }
    }
}
