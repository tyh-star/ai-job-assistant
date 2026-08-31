package aijob.util;

import aijob.dto.JobRequirementDraft;
import aijob.dto.TailorAnalysisRequirement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts required actions, object/domain, and methodology from a
 * responsibility requirement. Occupation-agnostic; display text is unchanged.
 */
public final class ResponsibilityConceptExtractor {

    private static final Set<String> METHODOLOGY = Set.of(
            "agile",
            "scrum",
            "kanban",
            "waterfall",
            "lean"
    );
    private static final Set<String> GENERIC_CONTEXT = Set.of(
            "application",
            "applications",
            "service",
            "services",
            "software",
            "code",
            "process",
            "processes",
            "team",
            "feature",
            "features",
            "production",
            "system",
            "systems",
            "program",
            "functionality",
            "functionalities",
            "workload",
            "workloads",
            "quality"
    );

    private ResponsibilityConceptExtractor() {
    }

    public static ResponsibilityConcept extract(TailorAnalysisRequirement requirement) {
        if (requirement == null) {
            return empty();
        }
        return extract(requirement.getRequirement(), requirement.getExactTerms());
    }

    public static ResponsibilityConcept extract(JobRequirementDraft draft) {
        if (draft == null) {
            return empty();
        }
        return extract(draft.getRequirement(), draft.getExactTerms());
    }

    public static ResponsibilityConcept extract(String requirement, List<String> exactTerms) {
        String text = requirement == null ? "" : requirement;
        List<String> actions = new ArrayList<>(ActionConceptRegistry.actionsIn(text));
        LinkedHashSet<String> technical = new LinkedHashSet<>();
        if (exactTerms != null) {
            for (String term : ExactTermPolicy.retainMeaningful(exactTerms)) {
                if (isTechnicalConcept(term)) {
                    technical.add(term.strip());
                }
            }
        }
        if (TermAliasNormalizer.matches(text, "mvc") && technical.stream().noneMatch(term ->
                TermAliasNormalizer.matches(term, "mvc"))) {
            technical.add("MVC");
        }
        if (TermAliasNormalizer.matches(text, "rest api") && technical.stream().noneMatch(term ->
                TermAliasNormalizer.matches(term, "rest api"))) {
            technical.add("REST API");
        }
        List<String> domains = new ArrayList<>(ObjectDomainRegistry.domainsIn(text));
        List<String> supporting = new ArrayList<>();
        for (String token : EvidenceText.normalize(text).split("[^a-z0-9+#]+")) {
            if (METHODOLOGY.contains(token) && !supporting.contains(token)) {
                supporting.add(token);
            }
        }
        return new ResponsibilityConcept(actions, new ArrayList<>(technical), domains, supporting);
    }

    public static String signature(String requirement, List<String> exactTerms) {
        ResponsibilityConcept concept = extract(requirement, exactTerms);
        return EvidenceText.normalize(requirement)
                + "|"
                + String.join(",", concept.requiredActions())
                + "|"
                + String.join(",", concept.requiredTechnicalConcepts())
                + "|"
                + String.join(",", concept.requiredObjectOrDomain())
                + "|"
                + String.join(",", concept.supportingContext());
    }

    private static ResponsibilityConcept empty() {
        return new ResponsibilityConcept(List.of(), List.of(), List.of(), List.of());
    }

    private static boolean isTechnicalConcept(String term) {
        if (term == null || term.isBlank() || ActionConceptRegistry.isActionToken(term) || isMethodology(term)) {
            return false;
        }
        if (!ObjectDomainRegistry.domainsIn(term).isEmpty()) {
            return false;
        }
        if (TermAliasNormalizer.matches(term, "mvc") || TermAliasNormalizer.matches(term, "rest api")) {
            return true;
        }
        String stripped = term.strip();
        if (stripped.length() >= 2 && stripped.length() <= 6 && stripped.equals(stripped.toUpperCase(Locale.ROOT))) {
            return stripped.chars().anyMatch(Character::isLetter);
        }
        boolean specific = false;
        for (String token : EvidenceText.normalize(term).split("[^a-z0-9+#]+")) {
            if (token.isBlank() || GENERIC_CONTEXT.contains(token)) {
                continue;
            }
            specific = true;
        }
        return specific;
    }

    private static boolean isMethodology(String term) {
        for (String token : EvidenceText.normalize(term).split("[^a-z0-9+#]+")) {
            if (METHODOLOGY.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
