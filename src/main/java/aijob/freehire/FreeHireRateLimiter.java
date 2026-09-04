package aijob.freehire;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A real per-minute request BUDGET for one FreeHire operation (e.g. search, or detail
 * reads), shared by every caller of this backend process -- deliberately NOT the
 * concurrency {@code Semaphore} {@link FreeHireClient} already carries.
 *
 * <p><b>Why this is a separate mechanism from the concurrency gate.</b> A
 * {@code Semaphore} bounds how many requests are in flight AT ONCE; it says nothing
 * about how many complete over a minute. A backend that fires 500 requests
 * sequentially, one at a time, each finishing in 10ms, never holds more than one
 * concurrent slot and would sail straight through any concurrency cap while still
 * hammering FreeHire at a rate no minute-based budget would allow. The two caps answer
 * different questions and are checked independently: this class answers "how many
 * requests has this MINUTE already spent", the semaphore answers "how many are in
 * flight RIGHT NOW".
 *
 * <p><b>Algorithm.</b> An approximate sliding window ("rolling window") counter: two
 * fixed buckets (the current minute and the one before it), and the previous bucket's
 * count is weighted by how much of it still overlaps the trailing 60-second window from
 * "now". This is the same approach widely used for cheap, O(1)-memory rate limiting
 * (e.g. Cloudflare's public description of their own limiter) -- it is not a perfectly
 * exact sliding log, but unlike a naive fixed-window counter it cannot be doubled by
 * bursting at a minute boundary (2x budget in a few seconds around :00), which is the
 * failure mode that actually matters for "prove a burst of fast requests cannot bypass
 * the budget".
 *
 * <p><b>Deliberately process-local.</b> This state lives in one JVM's heap. Today's
 * production topology is a single backend instance, so a process-local budget IS the
 * effective global budget. <b>If this deployment ever runs more than one backend
 * instance behind the same FreeHire account/egress, this class stops being a true
 * global limiter</b> -- each instance would enforce its own independent budget, and the
 * real aggregate call volume could reach N times what is configured here. Multi-instance
 * deployment must either (a) move this state to a shared store (e.g. Redis, mirroring
 * how FreeHire's own {@code internal/ratelimit} is backed by Redis for the same reason),
 * or (b) divide the configured per-minute budget by the instance count so the sum stays
 * within FreeHire's real limit. Not needed for the current single-instance production
 * topology; needed before scaling out.
 *
 * <p>Every method is safe to call from multiple threads at once; the counters are
 * updated under a single short-held lock, never blocking on I/O.
 */
public final class FreeHireRateLimiter {

    private final int budget;
    private final long windowMillis;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private long currentWindowStart;
    private int currentWindowCount;
    private int previousWindowCount;

    public FreeHireRateLimiter(int budgetPerMinute, Clock clock) {
        this(budgetPerMinute, Duration.ofMinutes(1), clock);
    }

    /** Package-visible so tests can use a shorter window than a real minute if useful. */
    FreeHireRateLimiter(int budgetPerWindow, Duration window, Clock clock) {
        if (budgetPerWindow < 1) {
            throw new IllegalArgumentException("budgetPerWindow must be at least 1");
        }
        if (window.toMillis() < 1) {
            throw new IllegalArgumentException("window must be at least 1ms");
        }
        this.budget = budgetPerWindow;
        this.windowMillis = window.toMillis();
        this.clock = clock;
        this.currentWindowStart = floorToWindow(clock.millis());
    }

    /**
     * Attempts to consume one unit of this minute's budget. Never blocks on anything but
     * the internal lock (held only for simple arithmetic) -- a caller gets an immediate
     * yes/no, never a wait for capacity to free up. That is deliberate: the whole point
     * of a fast "search is temporarily busy" response is that it returns quickly rather
     * than tying up a request thread.
     */
    public Outcome tryAcquire() {
        lock.lock();
        try {
            long now = clock.millis();
            rollWindowIfNeeded(now);
            double elapsedInCurrent = now - currentWindowStart;
            double overlap = Math.max(0.0, (windowMillis - elapsedInCurrent) / (double) windowMillis);
            double estimated = currentWindowCount + previousWindowCount * overlap;
            if (estimated >= budget) {
                long millisUntilWindowRolls = Math.max(0, windowMillis - (long) elapsedInCurrent);
                int retryAfterSeconds = (int) Math.max(1, Math.ceil(millisUntilWindowRolls / 1000.0));
                return new Outcome(false, retryAfterSeconds);
            }
            currentWindowCount++;
            return new Outcome(true, 0);
        } finally {
            lock.unlock();
        }
    }

    private void rollWindowIfNeeded(long now) {
        long windowStart = floorToWindow(now);
        if (windowStart == currentWindowStart) {
            return;
        }
        long windowsElapsed = (windowStart - currentWindowStart) / windowMillis;
        previousWindowCount = windowsElapsed == 1 ? currentWindowCount : 0;
        currentWindowCount = 0;
        currentWindowStart = windowStart;
    }

    private long floorToWindow(long epochMillis) {
        return epochMillis - Math.floorMod(epochMillis, windowMillis);
    }

    /**
     * @param allowed          whether the caller may proceed
     * @param retryAfterSeconds when {@code !allowed}, an estimate (this class's OWN
     *                          local policy, never a claim about FreeHire's own limit)
     *                          of how long until this budget is likely to have room
     *                          again; {@code 0} when {@code allowed} is true
     */
    public record Outcome(boolean allowed, int retryAfterSeconds) {
    }
}
