package app.pagefault.visionforge.api.model.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ReasoningEffort;
import app.pagefault.visionforge.agent.model.ReasoningReplayPolicy;

public record ModelResponse(
        UUID id,
        String displayName,
        String baseUrl,
        String modelId,
        ReasoningEffort reasoningEffort,
        ReasoningReplayPolicy reasoningReplay,
        int maxInputTokens,
        int maxOutputTokens,
        boolean enabled,
        boolean defaultModel,
        boolean apiKeyConfigured,
        Instant createdAt,
        Instant updatedAt) {

    public static ModelResponse from(ModelDefinition model) {
        return new ModelResponse(
                model.getId(),
                model.getDisplayName(),
                model.getBaseUrl(),
                model.getModelId(),
                model.getReasoningEffort(),
                model.getReasoningReplay(),
                model.getMaxInputTokens(),
                model.getMaxOutputTokens(),
                model.isEnabled(),
                model.isDefaultModel(),
                true,
                model.getCreatedAt(),
                model.getUpdatedAt());
    }
}
