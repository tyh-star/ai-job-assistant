package aijob.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceTopicsTest {

    @Test
    void metricCanonicalKeyIsNotDerivedFromEnglishAskableCopy() {
        assertEquals("metric", EvidenceTopics.topicKey("metric"));
        assertTrue(EvidenceTopics.isEnhancedMetricTopic("metric"));
        assertTrue(EvidenceTopics.isEnhancedCatalogTopic("maintain"));
        assertTrue(EvidenceTopics.isEnhancedCatalogTopic("testing"));
        assertTrue(EvidenceTopics.isEnhancedCatalogTopic("build-tool"));
        assertFalse(EvidenceTopics.isEnhancedMetricTopic("A confirmable performance or volume figure"));
        assertFalse(EvidenceTopics.isEnhancedCatalogTopic("A confirmable performance or volume figure"));
        assertEquals(
                "a confirmable performance or volume figure",
                EvidenceTopics.topicKey("A confirmable performance or volume figure"));
        assertFalse(EvidenceTopics.isEnhancedCatalogTopic("eq-PROJECT-prime-deals-metric"));
    }
}
