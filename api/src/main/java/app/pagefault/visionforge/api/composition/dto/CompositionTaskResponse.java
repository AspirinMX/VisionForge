package app.pagefault.visionforge.api.composition.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.agent.composition.CompositionInput;
import app.pagefault.visionforge.agent.composition.CompositionStatus;
import app.pagefault.visionforge.agent.composition.CompositionTask;

public record CompositionTaskResponse(
        UUID taskId,
        UUID conversationId,
        UUID storyboardFileId,
        int storyboardVersion,
        String outputSpec,
        CompositionStatus status,
        UUID outputAssetId,
        UUID outputFileId,
        UUID outputVersionId,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        List<CompositionInputResponse> inputs) {

    public static CompositionTaskResponse from(
            CompositionTask task, List<CompositionInput> inputs) {
        return new CompositionTaskResponse(
                task.getId(),
                task.getConversationId(),
                task.getStoryboardFileId(),
                task.getStoryboardVersion(),
                task.getOutputSpec(),
                task.getStatus(),
                task.getOutputAssetId(),
                task.getOutputFileId(),
                task.getOutputVersionId(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getCompletedAt(),
                inputs.stream().map(CompositionInputResponse::from).toList());
    }
}
