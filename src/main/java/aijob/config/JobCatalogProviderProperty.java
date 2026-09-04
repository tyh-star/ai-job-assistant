package aijob.config;

/**
 * The one property that selects which {@code JobCatalogService} bean is active:
 * {@code aijob.job-catalog.provider} ({@code JOB_CATALOG_PROVIDER} in the environment),
 * {@code freehire} (default) or {@code local_catalog} (explicit opt-in).
 *
 * <p>Both {@code JobCatalogServiceImpl} (local_catalog) and
 * {@code FreeHireJobCatalogService} (freehire) are annotated
 * {@code @ConditionalOnProperty} against these exact values, so exactly one is ever
 * registered — never both, never neither, and never chosen by which one the classpath
 * happens to construct first. Referenced from here rather than duplicated as a literal
 * in three places ({@code JobCatalogServiceImpl}, {@code FreeHireJobCatalogService},
 * {@code JobCatalogStartupBanner}) so the values cannot drift apart.
 */
public final class JobCatalogProviderProperty {

    public static final String PROPERTY = "aijob.job-catalog.provider";
    public static final String FREEHIRE = "freehire";
    public static final String LOCAL_CATALOG = "local_catalog";

    private JobCatalogProviderProperty() {
    }
}
