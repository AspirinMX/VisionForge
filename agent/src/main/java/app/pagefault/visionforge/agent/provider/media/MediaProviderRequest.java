package app.pagefault.visionforge.agent.provider.media;

import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.ExecutionMode;

/**
 * Domain-level media request. Provider adapters must not expose SDK types outside this package.
 */
public record MediaProviderRequest(
        UUID taskId,
        GenerationType type,
        String modelId,
        String prompt,
        String parameters,
        ExecutionMode executionMode,
        List<MediaProviderInput> inputs) {

    public MediaProviderRequest {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
}
