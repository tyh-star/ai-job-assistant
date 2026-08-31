package aijob.tailor.v3;

import java.util.List;

/**
 * Atomic coverage of one JD requirement for the Enhanced Opportunity Map.
 * Status is COVERED, PARTIALLY_COVERED, or MISSING. Internal topic keys are omitted.
 */
public record V3JdRequirementBreakdown(
        String requirementId,
        String requirementText,
        String status,
        List<String> coveredAtoms,
        List<String> missingAtoms,
        List<List<String>> alternativeGroups
) {
    public static final String COVERED = "COVERED";
    public static final String PARTIALLY_COVERED = "PARTIALLY_COVERED";
    public static final String MISSING = "MISSING";

    public V3JdRequirementBreakdown {
        requirementId = requirementId == null ? "" : requirementId;
        requirementText = requirementText == null ? "" : requirementText;
        status = status == null || status.isBlank() ? MISSING : status;
        coveredAtoms = coveredAtoms == null ? List.of() : List.copyOf(coveredAtoms);
        missingAtoms = missingAtoms == null ? List.of() : List.copyOf(missingAtoms);
        alternativeGroups = alternativeGroups == null ? List.of() : copyGroups(alternativeGroups);
    }

    private static List<List<String>> copyGroups(List<List<String>> groups) {
        return groups.stream().map(group -> group == null ? List.<String>of() : List.copyOf(group)).toList();
    }
}
