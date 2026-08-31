package aijob.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactTermPolicyTest {

    @Test
    void designAndImplementAreNotAtsTerms() {
        List<String> kept = ExactTermPolicy.retainMeaningful(List.of(
                "design", "implement", "Design", "implement"
        ));
        assertTrue(kept.isEmpty());
        assertTrue(ExactTermPolicy.isLowInformation("design"));
        assertTrue(ExactTermPolicy.isLowInformation("implement"));
    }

    @Test
    void springBootAndRestApiRemainAtsTerms() {
        List<String> kept = ExactTermPolicy.retainMeaningful(List.of(
                "Java", "Spring Boot", "REST API", "Maven", "MySQL", "AWS", "Docker",
                "Kubernetes", "CI/CD", "Microservices", "REST API development",
                "backend service development", "automated testing", "cloud deployment"
        ));
        assertTrue(kept.contains("Spring Boot"));
        assertTrue(kept.contains("REST API"));
        assertTrue(kept.contains("Docker"));
        assertTrue(kept.contains("REST API development"));
        assertFalse(kept.contains("design"));
    }

    @Test
    void responsibilityFallsBackToMeaningfulPhrase() {
        List<String> terms = ExactTermPolicy.termsForRequirement(
                "Design and implement backend services",
                List.of("Design", "implement")
        );
        assertEquals(List.of("backend services"), terms);
        assertFalse(terms.contains("Design"));
        assertFalse(terms.contains("implement"));
    }
}
