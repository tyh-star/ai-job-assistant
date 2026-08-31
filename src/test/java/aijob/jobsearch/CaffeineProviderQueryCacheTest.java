package aijob.jobsearch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaffeineProviderQueryCacheTest {

    @Test
    void sameKeyHitsCacheAndDoesNotReload() {
        CaffeineProviderQueryCache cache = new CaffeineProviderQueryCache();
        AtomicInteger loads = new AtomicInteger();
        ProviderSearchPage first = cache.getOrLoad("k", () -> {
            loads.incrementAndGet();
            return new ProviderSearchPage(List.of(), false, null);
        });
        ProviderSearchPage second = cache.getOrLoad("k", () -> {
            loads.incrementAndGet();
            return new ProviderSearchPage(List.of(), true, 9L);
        });
        assertEquals(1, loads.get());
        assertSame(first, second);
    }

    @Test
    void concurrentSameKeySingleFlight() throws Exception {
        CaffeineProviderQueryCache cache = new CaffeineProviderQueryCache();
        AtomicInteger loads = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(8);
        CountDownLatch done = new CountDownLatch(8);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    try {
                        start.await(2, TimeUnit.SECONDS);
                        cache.getOrLoad("same", () -> {
                            loads.incrementAndGet();
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            return new ProviderSearchPage(List.of(), false, null);
                        });
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, loads.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
