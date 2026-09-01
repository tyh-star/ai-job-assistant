package aijob.collector;

import aijob.enums.AtsProviderType;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SSRF guard for collection targets.
 *
 * <p>A {@code job_sources} row is operator-supplied configuration, not user input, but
 * it is still treated as untrusted: a tenant key becomes a single path segment on a
 * hard-coded official host, and any absolute URL is checked against the per-provider
 * allowlist. There is no code path that lets a caller point the collector at an
 * arbitrary address, so internal services, {@code localhost}, and cloud metadata
 * endpoints are unreachable by construction.
 */
public final class AtsHostAllowlist {

    private static final Map<AtsProviderType, Set<String>> ALLOWED_HOSTS = Map.of(
            AtsProviderType.GREENHOUSE, Set.of(
                    "boards-api.greenhouse.io",
                    "boards.greenhouse.io",
                    "job-boards.greenhouse.io"),
            AtsProviderType.LEVER, Set.of(
                    "api.lever.co",
                    "api.eu.lever.co",
                    "jobs.lever.co",
                    "jobs.eu.lever.co")
    );

    /**
     * Tenant keys are board tokens / site slugs. Restricting them to this shape stops
     * path traversal ({@code ../}), scheme injection, and userinfo tricks.
     */
    private static final Pattern TENANT_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private AtsHostAllowlist() {
    }

    public static Set<String> allowedHosts(AtsProviderType providerType) {
        return ALLOWED_HOSTS.getOrDefault(providerType, Set.of());
    }

    public static boolean isTenantKeyValid(String tenantKey) {
        return tenantKey != null && TENANT_KEY.matcher(tenantKey).matches();
    }

    public static String requireTenantKey(AtsProviderType providerType, String tenantKey) {
        if (!isTenantKeyValid(tenantKey)) {
            throw new JobCollectionException("INVALID_TENANT_KEY");
        }
        return tenantKey;
    }

    /**
     * @return true when {@code url} is an https URL on one of the provider's official
     *         hosts, with no embedded credentials and no port override.
     */
    public static boolean isAllowedUrl(AtsProviderType providerType, String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getUserInfo() != null || uri.getPort() != -1) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        return allowedHosts(providerType).contains(host.toLowerCase(Locale.ROOT));
    }

    public static void requireAllowedUrl(AtsProviderType providerType, String url) {
        if (!isAllowedUrl(providerType, url)) {
            throw new JobCollectionException("BLOCKED_TARGET_HOST");
        }
    }
}
