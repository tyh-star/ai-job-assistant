package aijob.tailor.v3;

public record V3GenerationTelemetry(
        String promptVersion,
        String model,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens
) {
    public static final String PROMPT_VERSION = "v3-2.4-action-object-provenance";
    public static final String ENHANCED_PROMPT_VERSION = "v3-3b-enhanced-user-confirmed";

    public V3GenerationTelemetry {
        promptVersion = promptVersion == null || promptVersion.isBlank()
                ? PROMPT_VERSION
                : promptVersion;
        model = model == null ? "" : model;
    }

    public static V3GenerationTelemetry mock() {
        return unknown(PROMPT_VERSION, "mock", 0L);
    }

    public static V3GenerationTelemetry unknown(String promptVersion, String model, long latencyMs) {
        return new V3GenerationTelemetry(promptVersion, model, latencyMs, null, null);
    }

    public static V3GenerationTelemetry of(
            String promptVersion,
            String model,
            long latencyMs,
            Integer promptTokens,
            Integer completionTokens) {
        return new V3GenerationTelemetry(promptVersion, model, latencyMs, promptTokens, completionTokens);
    }

    public Integer totalTokens() {
        if (promptTokens == null || completionTokens == null) {
            return null;
        }
        return promptTokens + completionTokens;
    }
}
