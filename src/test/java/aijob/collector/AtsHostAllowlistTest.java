package aijob.collector;

import aijob.enums.AtsProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF boundary for collection targets. A {@code job_sources} row is operator
 * configuration, but it is still the only externally influenced input that decides
 * where the collector connects, so it gets treated as untrusted.
 */
class AtsHostAllowlistTest {

    @Test
    void officialBoardHostsAreAllowed() {
        assertTrue(AtsHostAllowlist.isAllowedUrl(AtsProviderType.GREENHOUSE,
                "https://boards-api.greenhouse.io/v1/boards/acme/jobs"));
        assertTrue(AtsHostAllowlist.isAllowedUrl(AtsProviderType.LEVER,
                "https://api.lever.co/v0/postings/acme"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8080/admin",
            "https://localhost/v1/boards/acme",
            "https://127.0.0.1/v1/boards/acme",
            "https://[::1]/v1/boards/acme",
            "https://169.254.169.254/latest/meta-data/",
            "https://metadata.google.internal/computeMetadata/v1/",
            "https://10.0.0.5/internal",
            "https://192.168.1.10/internal",
            "https://mysql:3306/",
            "file:///etc/passwd",
    })
    void internalAndMetadataTargetsAreBlocked(String url) {
        assertFalse(AtsHostAllowlist.isAllowedUrl(AtsProviderType.GREENHOUSE, url));
        assertFalse(AtsHostAllowlist.isAllowedUrl(AtsProviderType.LEVER, url));
    }

    @Test
    void plainHttpIsRejectedEvenOnAnOfficialHost() {
        assertFalse(AtsHostAllowlist.isAllowedUrl(AtsProviderType.GREENHOUSE,
                "http://boards-api.greenhouse.io/v1/boards/acme"));
    }

    @Test
    void credentialsAndPortOverridesAreRejected() {
        assertFalse(AtsHostAllowlist.isAllowedUrl(AtsProviderType.GREENHOUSE,
                "https://user:pass@boards-api.greenhouse.io/v1/boards/acme"));
        assertFalse(AtsHostAllowlist.isAllowedUrl(AtsProviderType.GREENHOUSE,
                "https://boards-api.greenhouse.io:8443/v1/boards/acme"));
    }

    @Test
    void aLookalikeHostIsNotAnOfficialHost() {
        assertFalse(AtsHostAllowlist.isAllowedUrl(AtsProviderType.GREENHOUSE,
                "https://boards-api.greenhouse.io.attacker.example/v1/boards/acme"));
        assertFalse(AtsHostAllowlist.isAllowedUrl(AtsProviderType.LEVER,
                "https://api.lever.co.evil.example/v0/postings/acme"));
    }

    @Test
    void providersDoNotShareEachOthersHosts() {
        assertFalse(AtsHostAllowlist.isAllowedUrl(AtsProviderType.LEVER,
                "https://boards-api.greenhouse.io/v1/boards/acme"));
        assertFalse(AtsHostAllowlist.isAllowedUrl(AtsProviderType.GREENHOUSE,
                "https://api.lever.co/v0/postings/acme"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../../etc/passwd",
            "acme/../../secret",
            "acme?x=1",
            "acme#frag",
            "acme/jobs",
            "http://evil.example",
            "//evil.example",
            "user@host",
            " acme",
            "",
    })
    void malformedTenantKeysAreRejected(String tenantKey) {
        assertFalse(AtsHostAllowlist.isTenantKeyValid(tenantKey));
        assertThrows(JobCollectionException.class,
                () -> AtsHostAllowlist.requireTenantKey(AtsProviderType.GREENHOUSE, tenantKey));
    }

    @Test
    void nullTenantKeyIsRejected() {
        assertFalse(AtsHostAllowlist.isTenantKeyValid(null));
        assertThrows(JobCollectionException.class,
                () -> AtsHostAllowlist.requireTenantKey(AtsProviderType.LEVER, null));
    }

    @Test
    void ordinaryBoardTokensAreAccepted() {
        assertEquals("acme", AtsHostAllowlist.requireTenantKey(AtsProviderType.GREENHOUSE, "acme"));
        assertEquals("acme-eu", AtsHostAllowlist.requireTenantKey(AtsProviderType.LEVER, "acme-eu"));
        assertEquals("acme.io", AtsHostAllowlist.requireTenantKey(AtsProviderType.LEVER, "acme.io"));
    }

    @Test
    void requireAllowedUrlThrowsForABlockedTarget() {
        JobCollectionException error = assertThrows(JobCollectionException.class,
                () -> AtsHostAllowlist.requireAllowedUrl(
                        AtsProviderType.GREENHOUSE, "https://169.254.169.254/latest/meta-data/"));
        assertEquals("BLOCKED_TARGET_HOST", error.reason());
    }
}
