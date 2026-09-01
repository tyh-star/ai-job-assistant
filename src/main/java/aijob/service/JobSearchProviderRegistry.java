package aijob.service;

import aijob.exception.JobSearchUnavailableException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JobSearchProviderRegistry {

    public static final String PRIMARY_PROPERTY = "JOB_SEARCH_PRIMARY_PROVIDER";
    public static final String DEFAULT_PRIMARY = "JSEARCH";

    /**
     * Serves user searches from this deployment's own {@code job_catalog}. While it is
     * the primary provider, the outbound job-search providers are not even registered,
     * so no external job API can be reached from a user request.
     */
    public static final String LOCAL_CATALOG = "LOCAL_CATALOG";

    public static boolean isLocalCatalogPrimary(String configuredPrimary) {
        return configuredPrimary != null
                && LOCAL_CATALOG.equalsIgnoreCase(configuredPrimary.strip());
    }

    private final Map<String, JobSearchProvider> providersByName;
    private final Environment environment;

    public JobSearchProviderRegistry(List<JobSearchProvider> providers, Environment environment) {
        this.providersByName = providers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        provider -> provider.providerName().toUpperCase(Locale.ROOT),
                        Function.identity()));
        this.environment = environment;
    }

    public JobSearchProvider primary() {
        String name = environment.getProperty(PRIMARY_PROPERTY);
        if (name == null || name.isBlank()) {
            name = DEFAULT_PRIMARY;
        }
        return require(name.strip().toUpperCase(Locale.ROOT));
    }

    public JobSearchProvider require(String providerName) {
        JobSearchProvider provider = providersByName.get(providerName.toUpperCase(Locale.ROOT));
        if (provider == null) {
            throw new JobSearchUnavailableException(
                    "Job search is temporarily unavailable.",
                    JobSearchUnavailableException.REASON_CONFIG_MISSING,
                    null);
        }
        return provider;
    }
}
