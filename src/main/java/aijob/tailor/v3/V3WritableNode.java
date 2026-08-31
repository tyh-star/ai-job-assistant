package aijob.tailor.v3;

public record V3WritableNode(String nodeId, String originalText) {
    public V3WritableNode {
        nodeId = nodeId == null ? "" : nodeId;
        originalText = originalText == null ? "" : originalText;
    }
}
