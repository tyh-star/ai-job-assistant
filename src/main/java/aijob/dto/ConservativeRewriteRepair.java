package aijob.dto;

/**
 * One-shot CONSERVATIVE repair hint after filterSafeChanges rejected a node.
 */
public record ConservativeRewriteRepair(
        String reasonCode,
        String previousNewText
) {
}
