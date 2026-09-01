package aijob.config;

import aijob.AiJobApplication;
import aijob.dto.JobSearchRequest;
import aijob.jobsearch.ProviderSearchPage;
import aijob.service.JobSearchProvider;
import aijob.service.JobSearchProviderRegistry;
import aijob.service.impl.AdzunaJobSearchProvider;
import aijob.service.impl.JSearchJobSearchProvider;
import aijob.service.impl.LocalCatalogJobSearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With {@code JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG}, the number of JSearch calls
 * a user search can make is zero — not because the call site avoids it, but because
 * the outbound providers are never registered as beans, so there is nothing to call
 * on a zero-result page, on a retry, or on any future fallback path.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:localcatalogmode;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads",
                "JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG"
                // Note: no JSEARCH_API_KEY. LOCAL_CATALOG must start without one.
        }
)
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class LocalCatalogModeIsolationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JobSearchProviderRegistry registry;

    @Test
    void noOutboundJobSearchProviderIsEvenConstructed() {
        assertTrue(context.getBeansOfType(JSearchJobSearchProvider.class).isEmpty());
        assertTrue(context.getBeansOfType(AdzunaJobSearchProvider.class).isEmpty());
    }

    @Test
    void theOnlyRegisteredProviderIsTheLocalCatalog() {
        Map<String, JobSearchProvider> providers = context.getBeansOfType(JobSearchProvider.class);

        assertEquals(1, providers.size());
        assertInstanceOf(LocalCatalogJobSearchProvider.class, providers.values().iterator().next());
    }

    @Test
    void thePrimaryProviderReadsTheLocalDatabase() {
        JobSearchProvider primary = registry.primary();

        assertEquals(JobSearchProviderRegistry.LOCAL_CATALOG, primary.providerName());
        assertInstanceOf(LocalCatalogJobSearchProvider.class, primary);
    }

    @Test
    void aZeroResultSearchStaysLocalInsteadOfFallingBackToARemoteProvider() {
        JobSearchProvider primary = registry.primary();

        ProviderSearchPage page = primary.search(
                new JobSearchRequest("nothing will match this", null, null, 1, 20));

        assertTrue(page.results().isEmpty());
        assertFalse(page.hasMore());
        // Nothing to fall back to: the remote providers are absent from the context.
        assertEquals(List.of(), List.copyOf(context.getBeansOfType(JSearchJobSearchProvider.class).values()));
    }

    @Test
    void askingForJSearchByNameFailsRatherThanQuietlyReachingOut() {
        assertTrue(context.getBeansOfType(JSearchJobSearchProvider.class).isEmpty());
        try {
            registry.require("JSEARCH");
            throw new AssertionError("JSEARCH must not be resolvable in LOCAL_CATALOG mode");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("temporarily unavailable"));
        }
    }
}
