package aijob.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewritePiiTest {

    @Test
    void redactsAndRestoresEmailPhoneUrlAndName() {
        RewritePii pii = RewritePii.forNames(List.of("Alex Morgan"));
        String original = "Alex Morgan · alex.morgan@example.com · +1 (416) 555-0199 · linkedin.com/in/alexmorgan";
        String redacted = pii.redact(original);
        assertFalse(redacted.contains("Alex Morgan"));
        assertFalse(redacted.contains("@"));
        assertFalse(redacted.contains("555"));
        assertFalse(redacted.contains("linkedin"));
        assertTrue(RewritePii.onlyPersonalDetails(redacted));
        assertEquals(original, pii.restore(redacted));
    }

    @Test
    void keepsYearRangesAndMetrics() {
        RewritePii pii = RewritePii.forNames(List.of());
        String text = "Built reports 2019 - 2021 and cut costs by 40%";
        assertEquals(text, pii.redact(text));
    }

    @Test
    void sameValueGetsTheSamePlaceholder() {
        RewritePii pii = RewritePii.forNames(List.of());
        String first = pii.redact("mail a@example.com");
        String second = pii.redact("again a@example.com");
        assertEquals(first.substring(first.indexOf("[[")), second.substring(second.indexOf("[[")));
    }
}
