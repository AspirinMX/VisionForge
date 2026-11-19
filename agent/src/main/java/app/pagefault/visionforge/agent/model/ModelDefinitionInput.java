package app.pagefault.visionforge.agent.model;

import java.util.List;

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
        List<ReasoningEffort> supportedReasoningEfforts,
        Boolean clearReasoningEffort,
        ReasoningReplayPolicy reasoningReplay,
        Integer maxInputTokens,
        Integer maxOutputTokens,
        Boolean enabled,
        Boolean defaultModel,
        ModelCapabilityType capabilityType,
        MediaProtocol protocol,
        ExecutionMode executionMode,
        Long priceUnits,
        Boolean supportsImageInput) {
}
