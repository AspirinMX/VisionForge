package app.pagefault.visionforge.agent.model;

import java.util.UUID;

public record ModelProviderSettings(
        UUID modelDefinitionId,
        String baseUrl,
        String apiKey,
        String modelId,
        ReasoningEffort reasoningEffort,
        int maxInputTokens,
        int maxOutputTokens) {
}
