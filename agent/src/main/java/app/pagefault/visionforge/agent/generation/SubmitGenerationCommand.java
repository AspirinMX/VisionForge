package app.pagefault.visionforge.agent.generation;

import java.util.List;
import java.util.UUID;

public record SubmitGenerationCommand(
        UUID conversationId,
        UUID agentRunId,
        UUID toolInvocationId,
        GenerationType type,
        UUID modelDefinitionId,
        String prompt,
        String parameters,
        List<SubmitInput> inputs,
        String idempotencyKey,
        UUID sourceTaskId,
        String shotId,
        UUID storyboardFileId,
        Integer storyboardVersion) {

    public SubmitGenerationCommand {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
}
