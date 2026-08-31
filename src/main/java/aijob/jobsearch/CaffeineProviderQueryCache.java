package aijob.jobsearch;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

@Component
public class CaffeineProviderQueryCache implements ProviderQueryCache {

    static final int MAXIMUM_SIZE = 256;

    private final Cache<String, ProviderSearchPage> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(MAXIMUM_SIZE)
            .build();

    private final ConcurrentHashMap<String, CompletableFuture<ProviderSearchPage>> inflight =
            new ConcurrentHashMap<>();

    @Override
    public ProviderSearchPage getOrLoad(String cacheKey, Supplier<ProviderSearchPage> loader) {
        ProviderSearchPage cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<ProviderSearchPage> created = new CompletableFuture<>();
        CompletableFuture<ProviderSearchPage> existing = inflight.putIfAbsent(cacheKey, created);
        if (existing == null) {
            try {
                ProviderSearchPage loaded = loader.get();
                cache.put(cacheKey, loaded);
                created.complete(loaded);
                return loaded;
            } catch (RuntimeException ex) {
                created.completeExceptionally(ex);
                throw ex;
            } catch (Exception ex) {
                created.completeExceptionally(ex);
                throw new IllegalStateException(ex);
            } finally {
                inflight.remove(cacheKey, created);
            }
        }
        try {
            return existing.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for job search", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }
}
