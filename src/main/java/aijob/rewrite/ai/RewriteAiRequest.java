package aijob.rewrite.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @param model           provider model id, e.g. openai/gpt-oss-20b
 * @param systemPrompt    fixed rules
 * @param userPrompt      the task input (already redacted)
 * @param schemaName      json_schema name
 * @param schema          strict JSON schema for the response object
 * @param temperature     low for stable output
 * @param reasoningEffort gpt-oss reasoning effort: low, medium or high
 * @param maxOutputTokens upper bound for the completion
 */
public record RewriteAiRequest(
        String model,
        String systemPrompt,
        String userPrompt,
        String schemaName,
        JsonNode schema,
        double temperature,
        String reasoningEffort,
        int maxOutputTokens
) {
}
