package app.pagefault.visionforge.api.model.dto;

import java.util.UUID;

import app.pagefault.visionforge.agent.model.ExecutionMode;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.agent.model.ModelCapabilityType;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ReasoningEffort;

/**
 * End-user view of an enabled model. Credentials and upstream base URLs stay in the admin API.
 */
public record PublicModelResponse(
        UUID id,
        String displayName,
        String modelId,
        ModelCapabilityType capabilityType,
        MediaProtocol protocol,
        ExecutionMode executionMode,
        long priceUnits,
        boolean supportsImageInput,
        boolean defaultModel,
        ReasoningEffort reasoningEffort,
        Integer maxInputTokens,
        Integer maxOutputTokens) {

    public static PublicModelResponse from(ModelDefinition model) {
        return new PublicModelResponse(
                model.getId(),
                model.getDisplayName(),
                model.getModelId(),
                model.getCapabilityType(),
                model.getProtocol(),
                model.getExecutionMode(),
                model.getPriceUnits(),
                model.isSupportsImageInput(),
                model.isDefaultModel(),
                model.getReasoningEffort(),
                model.getMaxInputTokens(),
                model.getMaxOutputTokens());
    }
}
