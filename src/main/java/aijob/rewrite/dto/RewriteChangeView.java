package aijob.rewrite.dto;

/** One rewritten paragraph as shown to the user (personal details restored). */
public record RewriteChangeView(String nodeId, String originalText, String revisedText, String reason) {
}
