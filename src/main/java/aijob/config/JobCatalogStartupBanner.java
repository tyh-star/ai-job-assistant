package aijob.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * States, once per boot and in one line, what this process will actually do for job
 * search and collection — not profile-gated, so it prints in every context this
 * application starts in (the web API, the collector container, a one-shot collector
 * command, a test that boots the full context).
 *
 * <p>{@code Ordered.HIGHEST_PRECEDENCE} so it runs before any other
 * {@code ApplicationRunner} — including the collector's one-shot commands, which call
 * {@code System.exit} from inside their own {@code run()} and would otherwise take the
 * JVM down before this ever got to log anything.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JobCatalogStartupBanner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobCatalogStartupBanner.class);

    private final Environment environment;

    public JobCatalogStartupBanner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String provider = environment.getProperty(
                JobCatalogProviderProperty.PROPERTY, JobCatalogProviderProperty.FREEHIRE);
        boolean collectorEnabled = environment.getProperty(
                "aijob.collector.enabled", Boolean.class, false);
        boolean collectorProfileActive = java.util.Arrays.asList(environment.getActiveProfiles())
                .contains("collector");

        log.info("aijob startup: job-catalog.provider={} collector.enabled={} collector.profileActive={}"
                        + " (scheduled collection runs only when BOTH are true)",
                provider, collectorEnabled, collectorProfileActive);
    }
}
