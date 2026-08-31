package aijob.util;

import java.util.List;

/**
 * Server-side responsibility concept. Display text stays on the draft;
 * this record is matching-only.
 */
public record ResponsibilityConcept(
        List<String> requiredActions,
        List<String> requiredTechnicalConcepts,
        List<String> requiredObjectOrDomain,
        List<String> supportingContext
) {

    public ResponsibilityConcept {
        requiredActions = requiredActions == null ? List.of() : List.copyOf(requiredActions);
        requiredTechnicalConcepts = requiredTechnicalConcepts == null
                ? List.of()
                : List.copyOf(requiredTechnicalConcepts);
        requiredObjectOrDomain = requiredObjectOrDomain == null
                ? List.of()
                : List.copyOf(requiredObjectOrDomain);
        supportingContext = supportingContext == null ? List.of() : List.copyOf(supportingContext);
    }

    public boolean hasCoreActions() {
        return !requiredActions.isEmpty();
    }
}
