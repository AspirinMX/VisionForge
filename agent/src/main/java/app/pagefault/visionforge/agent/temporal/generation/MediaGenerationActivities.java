package app.pagefault.visionforge.agent.temporal.generation;

import java.util.UUID;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface MediaGenerationActivities {

    GenerationSubmitResult submit(UUID taskId);

    GenerationSubmitResult poll(UUID taskId);

    void settle(UUID taskId);

    void fail(UUID taskId, String errorMessage);
}
