package aijob.tailor.v3;

/**
 * One evidenced action-object pairing from a single original bullet.
 * Relations are never the Cartesian product of the whole entry.
 */
public record V3ActionObjectRelation(
        String action,
        String objectKey,
        String sourceNodeId,
        String surface
) {
    public V3ActionObjectRelation {
        action = action == null ? "" : action;
        objectKey = objectKey == null ? "" : objectKey;
        sourceNodeId = sourceNodeId == null ? "" : sourceNodeId;
        surface = surface == null ? "" : surface;
    }

    public String relationKey() {
        return action + "->" + objectKey;
    }
}
