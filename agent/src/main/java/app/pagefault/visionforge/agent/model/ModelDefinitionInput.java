package app.pagefault.visionforge.agent.model;

/**
 * Nullable command object. Create requires the mandatory fields; update applies only non-null
 * fields. A null API key keeps the existing key on update.
 */
public record ModelDefinitionInput(
        String displayName,
        String baseUrl,
        String apiKey,
        String modelId,
        ReasoningEffort reasoningEffort,
        Integer maxInputTokens,
        Integer maxOutputTokens,
        Boolean enabled,
        Boolean defaultModel) {
}
