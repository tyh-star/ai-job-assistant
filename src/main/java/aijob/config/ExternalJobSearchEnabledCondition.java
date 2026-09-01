package aijob.config;

import aijob.service.JobSearchProviderRegistry;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Keeps outbound job-search providers out of the application context while
 * {@code JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG}.
 *
 * <p>Guarding registration rather than the call site is deliberate: a provider that
 * was never constructed cannot be reached by a fallback, a retry, or a future code
 * path, so "zero JSearch calls" holds by construction instead of by convention.
 */
public class ExternalJobSearchEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String primary = context.getEnvironment()
                .getProperty(JobSearchProviderRegistry.PRIMARY_PROPERTY);
        return !JobSearchProviderRegistry.isLocalCatalogPrimary(primary);
    }
}
