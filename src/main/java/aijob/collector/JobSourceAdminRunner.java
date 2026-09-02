package aijob.collector;

import aijob.entity.JobCatalogEntry;
import aijob.entity.JobSource;
import aijob.enums.AtsProviderType;
import aijob.mapper.JobCatalogMapper;
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
 *
 * <p>Every command here terminates the process when it is done: 0 on success, non-zero
 * on failure. An admin command must not leave the container running, because that
 * container would then start collecting on the schedule -- which is emphatically not
 * what "add a source" asked for.
 */
@Component
@Profile("collector")
@Order(0)
public class JobSourceAdminRunner implements ApplicationRunner {

    static final String LIST_ARG = "aijob.sources.list";
    static final String ADD_ARG = "aijob.sources.add";
    static final String DUPLICATES_ARG = "aijob.sources.duplicates";

    private static final int DUPLICATE_SCAN_LIMIT = 200;
    private static final int DUPLICATES_PER_GROUP = 10;

    private static final Logger log = LoggerFactory.getLogger(JobSourceAdminRunner.class);

    private final JobSourceMapper jobSourceMapper;
    private final JobCatalogMapper jobCatalogMapper;
    private final JobCollectorProperties properties;
    private final CollectorProcessExit processExit;

    public JobSourceAdminRunner(JobSourceMapper jobSourceMapper,
                                JobCatalogMapper jobCatalogMapper,
                                JobCollectorProperties properties,
                                CollectorProcessExit processExit) {
        this.jobSourceMapper = jobSourceMapper;
        this.jobCatalogMapper = jobCatalogMapper;
        this.properties = properties;
        this.processExit = processExit;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean list = flag(args, LIST_ARG);
        boolean add = flag(args, ADD_ARG);
        boolean duplicates = flag(args, DUPLICATES_ARG);
        if (!list && !add && !duplicates) {
            // No admin command: this is an ordinary collector start, leave it alone.
            return;
        }

        int status = 0;
        try {
            if (list) {
                listSources();
            }
            if (duplicates) {
                reportSuspectedDuplicates();
            }
            if (add) {
                status = addSource(args);
            }
        } catch (RuntimeException ex) {
            log.error("Admin command failed ({})", ex.getClass().getSimpleName());
            status = 1;
        }
        processExit.exit(status);
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

    /**
     * Lists postings that share a company+title+location fingerprint.
     *
     * <p>This is the only thing the fingerprint is for. Ingestion never merges on it,
     * because one employer routinely runs several genuinely different openings under
     * the same title in the same city. Deciding that two of these are actually one
     * posting needs a human looking at the descriptions and requisition numbers.
     */
    private void reportSuspectedDuplicates() {
        List<JobCatalogEntry> sample = jobCatalogMapper.findRecentActive(DUPLICATE_SCAN_LIMIT);
        int groups = 0;
        for (JobCatalogEntry entry : sample) {
            List<JobCatalogEntry> others = jobCatalogMapper.findSuspectedDuplicates(
                    entry.getFingerprint(), entry.getId(), DUPLICATES_PER_GROUP);
            if (others.isEmpty()) {
                continue;
            }
            groups++;
            log.info("Suspected duplicate group: {} / {} / {}",
                    entry.getCompanyName(), entry.getTitle(), entry.getLocationText());
            log.info("  catalog {} -> {}", entry.getId(), entry.getApplyUrl());
            for (JobCatalogEntry other : others) {
                log.info("  catalog {} -> {}", other.getId(), other.getApplyUrl());
            }
        }
        log.info("Scanned {} recent active postings; {} shared a fingerprint with another. "
                        + "These are candidates for review, not automatic merges.",
                sample.size(), groups);
    }

    /** @return the process exit status: 0 when the source was created. */
    private int addSource(ApplicationArguments args) {
        String providerRaw = single(args, "aijob.sources.provider");
        String tenant = single(args, "aijob.sources.tenant");
        String displayName = single(args, "aijob.sources.display-name");

        AtsProviderType provider = parseProvider(providerRaw);
        if (provider == null) {
            log.error("--aijob.sources.provider must be one of GREENHOUSE, LEVER");
            return 2;
        }
        if (!AtsHostAllowlist.isTenantKeyValid(tenant)) {
            log.error("--aijob.sources.tenant must be a board token or site slug "
                    + "matching [A-Za-z0-9][A-Za-z0-9._-]{0,99}");
            return 2;
        }
        if (displayName == null || displayName.isBlank()) {
            log.error("--aijob.sources.display-name is required");
            return 2;
        }

        String baseUrl = provider == AtsProviderType.GREENHOUSE
                ? JobCollectorClientConfig.GREENHOUSE_BASE_URL
                : JobCollectorClientConfig.LEVER_BASE_URL;
        // The fetch target is checked against the provider's official hosts before it
        // is ever stored, so a stored base_url can never send the collector somewhere
        // else. (Display links, such as a posting's apply URL, are a different concern
        // and are NOT held to this allowlist -- see ExternalLinks.)
        AtsHostAllowlist.requireAllowedUrl(provider, baseUrl);

        JobSource source = new JobSource();
        source.setProviderType(provider.name());
        source.setTenantKey(tenant);
        source.setDisplayName(displayName.strip());
        source.setBaseUrl(baseUrl);
        // New sources start disabled on both flags; review the board's terms first.
        source.setEnabled(false);
        source.setCrawlAllowed(false);
        source.setTermsUrl(single(args, "aijob.sources.terms-url"));
        source.setCollectionIntervalMinutes(properties.getIntervalMinutes());
        source.setNextRunAt(LocalDateTime.now());

        try {
            jobSourceMapper.insert(source);
        } catch (RuntimeException ex) {
            log.error("Could not create the job source; a row for {}/{} may already exist",
                    provider, tenant);
            return 1;
        }
        log.info("Created job source {} ({} / {}), disabled. Enable it with SQL once "
                        + "its terms allow automated access.",
                source.getId(), provider, tenant);
        return 0;
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
