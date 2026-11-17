package app.pagefault.visionforge.api.generation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.agent.generation.GenerationBillingStatus;
import app.pagefault.visionforge.agent.generation.GenerationCancelStatus;
import app.pagefault.visionforge.agent.generation.GenerationDeliveryStatus;
import app.pagefault.visionforge.agent.generation.GenerationInput;
import app.pagefault.visionforge.agent.generation.GenerationOutput;
import app.pagefault.visionforge.agent.generation.GenerationStatus;
import app.pagefault.visionforge.agent.generation.GenerationTask;
import app.pagefault.visionforge.agent.generation.GenerationType;

public record GenerationTaskResponse(
        UUID taskId,
        UUID conversationId,
        GenerationType type,
        UUID modelDefinitionId,
        String prompt,
        String parameters,
        GenerationStatus status,
        String providerStatus,
        GenerationDeliveryStatus deliveryStatus,
        GenerationBillingStatus billingStatus,
        GenerationCancelStatus cancelStatus,
        long priceUnits,
        UUID outputFileId,
        String errorMessage,
        UUID sourceTaskId,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        List<GenerationInputResponse> inputs,
        List<GenerationOutputResponse> outputs) {

    public static GenerationTaskResponse from(
            GenerationTask task,
            List<GenerationInput> inputs,
            List<GenerationOutput> outputs) {
        return new GenerationTaskResponse(
                task.getId(),
                task.getConversationId(),
                task.getType(),
                task.getModelDefinitionId(),
                task.getPrompt(),
                task.getParameters(),
                task.getStatus(),
                task.getProviderStatus(),
                task.getDeliveryStatus(),
                task.getBillingStatus(),
                task.getCancelStatus(),
                task.getPriceUnits(),
                task.getOutputFileId(),
                task.getErrorMessage(),
                task.getSourceTaskId(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getCompletedAt(),
                inputs.stream().map(GenerationInputResponse::from).toList(),
                outputs.stream().map(GenerationOutputResponse::from).toList());
    }
}
