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

    // ---------------------------------------------------------------------------
    // Query strings: an identifier often lives there, so it must survive.
    // ---------------------------------------------------------------------------

    @Test
    void aJobIdentifierInTheQueryStringIsKept() {
        assertEquals("acme.example.com/careers?gh_jid=1001",
                CatalogFingerprint.normalizeUrl("https://acme.example.com/careers?gh_jid=1001"));
        assertEquals("acme.example.com/apply?token=abc123",
                CatalogFingerprint.normalizeUrl("https://acme.example.com/apply?token=abc123"));
        assertEquals("acme.example.com/p?jobId=77",
                CatalogFingerprint.normalizeUrl("https://acme.example.com/p?jobId=77"));
    }

    @Test
    void twoPostingsThatDifferOnlyInTheirJobIdParameterStayDifferent() {
        assertNotEquals(
                CatalogFingerprint.identityUrl("https://acme.example.com/careers?gh_jid=1001"),
                CatalogFingerprint.identityUrl("https://acme.example.com/careers?gh_jid=1002"));
    }

    @Test
    void refAndSourceAreNotTreatedAsTrackingNoise() {
        // Real boards use these as part of a posting's address. Discarding them by
        // assumption would merge distinct openings.
        assertNotEquals(
                CatalogFingerprint.identityUrl("https://acme.example.com/jobs/x?ref=eng-1"),
                CatalogFingerprint.identityUrl("https://acme.example.com/jobs/x?ref=eng-2"));
        assertNotEquals(
                CatalogFingerprint.identityUrl("https://acme.example.com/jobs/x?source=a"),
                CatalogFingerprint.identityUrl("https://acme.example.com/jobs/x?source=b"));
    }

    @Test
    void unknownQueryParametersAreKeptByDefault() {
        assertEquals("acme.example.com/jobs/x?somethingNew=42",
                CatalogFingerprint.normalizeUrl("https://acme.example.com/jobs/x?somethingNew=42"));
    }

    @Test
    void onlyConfirmedCampaignParametersAreDropped() {
        String expected = "acme.example.com/jobs/x?gh_jid=5";

        assertEquals(expected, CatalogFingerprint.normalizeUrl(
                "https://acme.example.com/jobs/x?gh_jid=5&utm_source=n&utm_campaign=c"));
        assertEquals(expected, CatalogFingerprint.normalizeUrl(
                "https://acme.example.com/jobs/x?gh_src=abc&gh_jid=5&fbclid=z&gclid=q"));
    }

    @Test
    void parameterOrderDoesNotChangeTheKey() {
        assertEquals(
                CatalogFingerprint.normalizeUrl("https://acme.example.com/jobs/x?a=1&b=2"),
                CatalogFingerprint.normalizeUrl("https://acme.example.com/jobs/x?b=2&a=1"));
    }

    // ---------------------------------------------------------------------------
    // identityUrl: only URLs that actually name one opening become merge keys.
    // ---------------------------------------------------------------------------

    @Test
    void aPostingSpecificUrlIsAValidMergeKey() {
        assertEquals("boards.greenhouse.io/acme/jobs/1",
                CatalogFingerprint.identityUrl("https://boards.greenhouse.io/acme/jobs/1"));
        assertEquals("jobs.lever.co/acme/0d1e-2f3a",
                CatalogFingerprint.identityUrl("https://jobs.lever.co/acme/0d1e-2f3a"));
        assertEquals("acme.example.com/careers/staff-engineer",
                CatalogFingerprint.identityUrl("https://acme.example.com/careers/staff-engineer"));
    }

    @Test
    void aGenericCareersPageIsNeverAMergeKey() {
        // Every posting on a board can carry one of these, so merging on it would
        // collapse the whole board into a single catalog row.
        assertNull(CatalogFingerprint.identityUrl("https://acme.example.com"));
        assertNull(CatalogFingerprint.identityUrl("https://acme.example.com/"));
        assertNull(CatalogFingerprint.identityUrl("https://acme.example.com/careers"));
        assertNull(CatalogFingerprint.identityUrl("https://acme.example.com/jobs/"));
        assertNull(CatalogFingerprint.identityUrl("https://acme.example.com/en/careers"));
        assertNull(CatalogFingerprint.identityUrl("https://acme.example.com/careers/jobs"));
        assertNull(CatalogFingerprint.identityUrl("https://acme.example.com/join-us"));
    }

    @Test
    void aListingFilterDoesNotTurnACareersPageIntoAPosting() {
        assertNull(CatalogFingerprint.identityUrl("https://acme.example.com/careers?lang=en"));
        assertNull(CatalogFingerprint.identityUrl("https://acme.example.com/careers?page=2&sort=date"));
        assertNull(CatalogFingerprint.identityUrl("https://acme.example.com/careers?department=eng"));
    }

    @Test
    void aCareersPageWithAJobIdentifierIsAPosting() {
        assertEquals("acme.example.com/careers?gh_jid=1001",
                CatalogFingerprint.identityUrl("https://acme.example.com/careers?gh_jid=1001"));
    }

    @Test
    void aBoardRootIsNotAPosting() {
        // One segment past the host is a tenant or section root.
        assertNull(CatalogFingerprint.identityUrl("https://boards.greenhouse.io/acme"));
        assertNull(CatalogFingerprint.identityUrl("https://jobs.lever.co/acme"));
    }

    @Test
    void anUnusableUrlHasNoIdentity() {
        assertNull(CatalogFingerprint.identityUrl(null));
        assertNull(CatalogFingerprint.identityUrl("   "));
        assertNull(CatalogFingerprint.identityUrl("https://"));
    }
}
