package aijob.freehire;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FreeHireRateLimiter} in isolation, with a controllable clock -- no test here
 * sleeps for anything close to a real minute; the clock is advanced programmatically.
 */
class FreeHireRateLimiterTest {

    @Test
    void allowsExactlyTheBudgetThenRejects() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        FreeHireRateLimiter limiter = new FreeHireRateLimiter(3, clock);

        assertTrue(limiter.tryAcquire().allowed());
        assertTrue(limiter.tryAcquire().allowed());
        assertTrue(limiter.tryAcquire().allowed());

        FreeHireRateLimiter.Outcome rejected = limiter.tryAcquire();
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterSeconds() > 0, "a rejection must carry a positive retry estimate");
    }

    @Test
    void manyFastCallsAtTheSameInstantCannotExceedTheBudget() {
        // The clock never advances during this loop -- proving raw call speed (zero
        // elapsed time) is not itself a way around the cap.
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        FreeHireRateLimiter limiter = new FreeHireRateLimiter(5, clock);

        int allowed = 0;
        for (int i = 0; i < 500; i++) {
            if (limiter.tryAcquire().allowed()) {
                allowed++;
            }
        }
        assertEquals(5, allowed);
    }

    @Test
    void budgetIsSharedNotPerCaller() {
        // The limiter has no notion of "caller" at all -- two logically different users
        // calling tryAcquire() on the SAME instance draw from the SAME pool. This is the
        // entire point: one FreeHireRateLimiter instance per operation, backend-wide.
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        FreeHireRateLimiter limiter = new FreeHireRateLimiter(2, clock);

        assertTrue(limiter.tryAcquire().allowed(), "user A's first call");
        assertTrue(limiter.tryAcquire().allowed(), "user B's first call spends the SAME budget");
        assertFalse(limiter.tryAcquire().allowed(), "user A's second call finds the shared budget already gone");
    }

    @Test
    void aFullMinutePastTheLastWindowStartsAFreshBudget() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        FreeHireRateLimiter limiter = new FreeHireRateLimiter(2, clock);

        assertTrue(limiter.tryAcquire().allowed());
        assertTrue(limiter.tryAcquire().allowed());
        assertFalse(limiter.tryAcquire().allowed());

        // TWO full minutes later, not just barely over one: at exactly ~61s the previous
        // window (which held 2 requests) still overlaps the new window by ~98%, so the
        // sliding-window estimate would still be close to the cap and this assertion
        // would be testing the near-boundary blending behavior by accident -- that case
        // is covered on its own, deliberately, by
        // aBurstRightAtAMinuteBoundaryStillCannotDoubleTheBudget below. Advancing well
        // past two full windows guarantees the previous window's weighted contribution
        // is genuinely zero (windowsElapsed > 1), isolating what this test actually means
        // to check: a FRESH window with no residual weighting at all.
        clock.advance(Duration.ofSeconds(125));

        assertTrue(limiter.tryAcquire().allowed(), "a new minute must carry a fresh budget");
        assertTrue(limiter.tryAcquire().allowed());
        assertFalse(limiter.tryAcquire().allowed(), "and that fresh budget is still finite");
    }

    @Test
    void aBurstRightAtAMinuteBoundaryStillCannotDoubleTheBudget() {
        // This is the specific failure mode a NAIVE fixed-window counter has: spend the
        // whole budget in the last instant of one window, then spend it again in the
        // first instant of the next, for ~2x budget in a few seconds. The approximate
        // sliding window here weights the previous window's count by how much it still
        // overlaps, so it must not allow anywhere near that -- though, being an
        // APPROXIMATE (not exact) sliding window, it can still let a SLIVER of extra
        // capacity through right at the boundary (see the class javadoc: "not a
        // perfectly exact sliding log"). What must never happen is anything close to a
        // full second budget on top of the first.
        int budget = 10;
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:59.900Z"));
        FreeHireRateLimiter limiter = new FreeHireRateLimiter(budget, clock);

        int grantedBeforeBoundary = 0;
        for (int i = 0; i < budget; i++) {
            if (limiter.tryAcquire().allowed()) {
                grantedBeforeBoundary++;
            }
        }
        assertEquals(budget, grantedBeforeBoundary, "the whole budget must be spendable just before the boundary");
        assertFalse(limiter.tryAcquire().allowed(), "and no more than the budget, even still before the boundary");

        // 200ms later: technically the NEXT minute (12:01:00.100), but still ~99.8%
        // overlapping the previous window from a sliding-60s perspective.
        clock.advance(Duration.ofMillis(200));
        int grantedJustAfterBoundary = 0;
        for (int i = 0; i < budget; i++) {
            if (limiter.tryAcquire().allowed()) {
                grantedJustAfterBoundary++;
            }
        }

        assertTrue(grantedJustAfterBoundary <= 1,
                "right after the boundary the previous window's weighted count leaves at most a "
                        + "sliver of headroom, never a whole fresh " + budget + "-request budget: granted "
                        + grantedJustAfterBoundary);
        int totalGranted = grantedBeforeBoundary + grantedJustAfterBoundary;
        assertTrue(totalGranted < budget * 2,
                "a burst spanning the boundary must stay well under double the budget: granted " + totalGranted
                        + " of a possible " + (budget * 2));
    }

    @Test
    void manyConcurrentThreadsCannotExceedTheBudgetEitherEvenThoughTheClockNeverMoves() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        int budget = 25;
        FreeHireRateLimiter limiter = new FreeHireRateLimiter(budget, clock);

        int threadCount = 200;
        // The pool must be able to run every task AT ONCE: each task blocks on `go`
        // (a rendezvous barrier) before doing its real work, so a pool smaller than
        // threadCount deadlocks -- the running threads all park on `go`, the still-queued
        // ones never get a worker to run on and so never reach `ready.countDown()`, and
        // the main thread's `ready.await()` below waits forever. Confirmed by hitting
        // exactly this deadlock with a 32-thread pool during this round's verification.
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (limiter.tryAcquire().allowed()) {
                    allowed.incrementAndGet();
                }
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(budget, allowed.get(),
                "raw thread concurrency must not let more than the configured budget through");
    }

    @Test
    void rejectsAConstructionWithNoBudget() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new FreeHireRateLimiter(0, Clock.systemUTC()));
    }

    /** A {@link Clock} a test can advance without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration by) {
            instant = instant.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
