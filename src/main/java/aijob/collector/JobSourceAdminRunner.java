package aijob.collector;

import aijob.entity.JobSource;
import aijob.enums.AtsProviderType;
import aijob.mapper.JobSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Operator CLI for {@code job_sources}. Deliberately not an HTTP endpoint: users must
 * never be able to point the collector at a URL of their choosing.
 *
 * <p>Runs inside the collector container, for example:
 * <pre>
 * docker compose ... run --rm collector \
 *   --aijob.sources.add=true \
 *   --aijob.sources.provider=GREENHOUSE \
 *   --aijob.sources.tenant=examplecorp \
 *   --aijob.sources.display-name="Example Corp" \
 *   --aijob.sources.terms-url=https://example.com/careers-terms
 * </pre>
 *
 * <p>A new source is always created disabled. Enabling it is a separate, deliberate
 * step once its terms have been reviewed.
 */
@Component
@Profile("collector")
@Order(0)
public class JobSourceAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobSourceAdminRunner.class);

    private final JobSourceMapper jobSourceMapper;
    private final JobCollectorProperties properties;

    public JobSourceAdminRunner(JobSourceMapper jobSourceMapper, JobCollectorProperties properties) {
        this.jobSourceMapper = jobSourceMapper;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (flag(args, "aijob.sources.list")) {
            listSources();
        }
        if (flag(args, "aijob.sources.add")) {
            addSource(args);
        }
    }

    private void listSources() {
        List<JobSource> sources = jobSourceMapper.findAll();
        if (sources.isEmpty()) {
            log.info("No job sources are configured");
            return;
        }
        for (JobSource source : sources) {
            log.info("Source {} provider={} tenant={} enabled={} crawlAllowed={} "
                            + "interval={}m lastSuccess={} nextRun={} failures={}",
                    source.getId(), source.getProviderType(), source.getTenantKey(),
                    source.isEnabled(), source.isCrawlAllowed(),
                    source.getCollectionIntervalMinutes(), source.getLastSuccessAt(),
                    source.getNextRunAt(), source.getConsecutiveFailures());
        }
    }

    private void addSource(ApplicationArguments args) {
        String providerRaw = single(args, "aijob.sources.provider");
        String tenant = single(args, "aijob.sources.tenant");
        String displayName = single(args, "aijob.sources.display-name");

        AtsProviderType provider = parseProvider(providerRaw);
        if (provider == null) {
            log.error("--aijob.sources.provider must be one of GREENHOUSE, LEVER");
            return;
        }
        if (!AtsHostAllowlist.isTenantKeyValid(tenant)) {
            log.error("--aijob.sources.tenant must be a board token or site slug "
                    + "matching [A-Za-z0-9][A-Za-z0-9._-]{0,99}");
            return;
        }
        if (displayName == null || displayName.isBlank()) {
            log.error("--aijob.sources.display-name is required");
            return;
        }

        JobSource source = new JobSource();
        source.setProviderType(provider.name());
        source.setTenantKey(tenant);
        source.setDisplayName(displayName.strip());
        source.setBaseUrl(provider == AtsProviderType.GREENHOUSE
                ? JobCollectorClientConfig.GREENHOUSE_BASE_URL
                : JobCollectorClientConfig.LEVER_BASE_URL);
        // New sources start disabled on both flags; review the board's terms first.
        source.setEnabled(false);
        source.setCrawlAllowed(false);
        source.setTermsUrl(single(args, "aijob.sources.terms-url"));
        source.setCollectionIntervalMinutes(properties.getIntervalMinutes());
        source.setNextRunAt(LocalDateTime.now());

        try {
            jobSourceMapper.insert(source);
            log.info("Created job source {} ({} / {}), disabled. Enable it with SQL once "
                            + "its terms allow automated access.",
                    source.getId(), provider, tenant);
        } catch (RuntimeException ex) {
            log.error("Could not create the job source; a row for {}/{} may already exist",
                    provider, tenant);
        }
    }

    private static AtsProviderType parseProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AtsProviderType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean flag(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {
            return false;
        }
        return values.isEmpty() || "true".equalsIgnoreCase(values.get(values.size() - 1));
    }

    private static String single(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(values.size() - 1);
    }
}
