package aijob.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogFingerprintTest {

    @Test
    void theSamePostingSeenTwiceProducesTheSameFingerprint() {
        assertEquals(
                CatalogFingerprint.of("Acme", "Java Developer", "Toronto, ON"),
                CatalogFingerprint.of("  acme ", "JAVA   Developer", "toronto, on"));
    }

    @Test
    void aFingerprintIsAHexSha256AndCarriesNoDatabaseIdentity() {
        String fingerprint = CatalogFingerprint.of("Acme", "Java Developer", "Toronto");

        assertEquals(64, fingerprint.length());
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
    }

    @Test
    void companyAndLocationBothChangeTheFingerprint() {
        String base = CatalogFingerprint.of("Acme", "Java Developer", "Toronto");

        assertNotEquals(base, CatalogFingerprint.of("Globex", "Java Developer", "Toronto"));
        assertNotEquals(base, CatalogFingerprint.of("Acme", "Java Developer", "Vancouver"));
        assertNotEquals(base, CatalogFingerprint.of("Acme", "Python Developer", "Toronto"));
    }

    @Test
    void urlNormalizationIgnoresSchemeHostCaseTrackingAndTrailingSlash() {
        String expected = "boards.greenhouse.io/acme/jobs/1";

        assertEquals(expected, CatalogFingerprint.normalizeUrl("https://boards.greenhouse.io/acme/jobs/1"));
        assertEquals(expected, CatalogFingerprint.normalizeUrl("http://boards.greenhouse.io/acme/jobs/1/"));
        assertEquals(expected, CatalogFingerprint.normalizeUrl("https://www.boards.greenhouse.io/acme/jobs/1"));
        assertEquals(expected, CatalogFingerprint.normalizeUrl(
                "https://Boards.Greenhouse.IO/acme/jobs/1?utm_source=x&gh_src=y"));
        assertEquals(expected, CatalogFingerprint.normalizeUrl(
                "  https://boards.greenhouse.io/acme/jobs/1#apply  "));
    }

    @Test
    void differentPostingsDoNotNormalizeToTheSameUrl() {
        assertNotEquals(
                CatalogFingerprint.normalizeUrl("https://boards.greenhouse.io/acme/jobs/1"),
                CatalogFingerprint.normalizeUrl("https://boards.greenhouse.io/acme/jobs/2"));
    }

    @Test
    void missingUrlsNormalizeToNullRatherThanAnEmptyMatchKey() {
        assertNull(CatalogFingerprint.normalizeUrl(null));
        assertNull(CatalogFingerprint.normalizeUrl("   "));
        assertNull(CatalogFingerprint.normalizeUrl("https://"));
    }

    @Test
    void nullPartsAreTreatedAsEmptyRatherThanThrowing() {
        assertEquals(
                CatalogFingerprint.of(null, null, null),
                CatalogFingerprint.of("", "", ""));
    }
}
