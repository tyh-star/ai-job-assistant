package aijob.jobsearch;

import java.util.function.Supplier;

public interface ProviderQueryCache {

    ProviderSearchPage getOrLoad(String cacheKey, Supplier<ProviderSearchPage> loader);
}
