package aijob.util;

import aijob.dto.RejectedTailorChange;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every ActionConcept in a candidate must already exist in the target original.
 * Supporting-node actions cannot be transferred onto the target.
 */
public final class ActionProvenanceGate {

    private static final Pattern DANGLING_GERUND = Pattern.compile(
            "(?i),\\s+(?:using|deploying|implementing|applying|enabling|creating|building|"
                    + "designing|maintaining|optimizing|integrating)\\b"
    );

    private ActionProvenanceGate() {
    }

    public static String rejectReason(String targetOriginal, String newText) {
        if (newText == null || newText.isBlank()) {
            return null;
        }
        Set<String> originalActions = ActionConceptRegistry.actionsIn(targetOriginal);
        Set<String> candidateActions = ActionConceptRegistry.actionsIn(newText);
        for (String action : candidateActions) {
            if (!originalActions.contains(action)) {
                return RejectedTailorChange.UNSUPPORTED_ACTION;
            }
        }
        if (hasDanglingUnsupportedGerund(targetOriginal, newText, originalActions)) {
            return RejectedTailorChange.UNSUPPORTED_ACTION;
        }
        return null;
    }

    private static boolean hasDanglingUnsupportedGerund(
            String targetOriginal,
            String newText,
            Set<String> originalActions) {

        if (targetOriginal != null && DANGLING_GERUND.matcher(targetOriginal).find()) {
            return false;
        }
        if (!DANGLING_GERUND.matcher(newText == null ? "" : newText).find()) {
            return false;
        }
        Set<String> extra = new LinkedHashSet<>(ActionConceptRegistry.actionsIn(newText));
        extra.removeAll(originalActions);
        if (!extra.isEmpty()) {
            return true;
        }
        String lower = newText.toLowerCase(Locale.ROOT);
        return (lower.contains("deploying")
                && !ActionConceptRegistry.contains(targetOriginal, ActionConceptRegistry.DEPLOY))
                || (lower.contains("implementing")
                && !ActionConceptRegistry.contains(targetOriginal, ActionConceptRegistry.IMPLEMENT))
                || (lower.contains("optimizing")
                && !ActionConceptRegistry.contains(targetOriginal, ActionConceptRegistry.OPTIMIZE))
                || (lower.contains("maintaining")
                && !ActionConceptRegistry.contains(targetOriginal, ActionConceptRegistry.MAINTAIN));
    }
}
