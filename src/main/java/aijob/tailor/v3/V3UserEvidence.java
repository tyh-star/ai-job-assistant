package aijob.tailor.v3;

/**
 * Read-only view of a stored evidence item. The evidence table remains the
 * source of truth for confirmed/rejected facts. Proposal envelopes may snapshot
 * {@code evidenceItemId} but must not keep an independent answer body.
 */
public record V3UserEvidence(
        Long evidenceItemId,
        String storedEntryKey,
        String answerStatus,
        String actionText,
        String resultText,
        String metricText,
        String contextText,
        String topicLabel,
        String topicKey
) {
    public V3UserEvidence {
        storedEntryKey = storedEntryKey == null ? "" : storedEntryKey.strip();
        answerStatus = answerStatus == null ? "" : answerStatus.strip();
        actionText = actionText == null ? "" : actionText;
        resultText = resultText == null ? "" : resultText;
        metricText = metricText == null ? "" : metricText;
        contextText = contextText == null ? "" : contextText;
        topicLabel = topicLabel == null ? "" : topicLabel;
        topicKey = topicKey == null || topicKey.isBlank()
                ? aijob.util.EvidenceTopics.topicKey(topicLabel)
                : aijob.util.EvidenceTopics.topicKey(topicKey);
    }

    public V3UserEvidence(
            Long evidenceItemId,
            String storedEntryKey,
            String answerStatus,
            String actionText,
            String resultText,
            String metricText,
            String contextText,
            String topicLabel) {
        this(evidenceItemId, storedEntryKey, answerStatus, actionText, resultText, metricText, contextText, topicLabel, topicLabel);
    }

    public boolean confirmed() {
        return "CONFIRMED_USED".equals(answerStatus);
    }

    public boolean rejected() {
        return "CONFIRMED_NOT_USED".equals(answerStatus);
    }

    public String combinedText() {
        return String.join(" ", actionText, resultText, metricText, contextText, topicLabel).strip();
    }

    public String displayText() {
        return String.join(" ", actionText, resultText, metricText, contextText).strip();
    }
}
