package app.pagefault.visionforge.agent.temporal.generation;

import java.util.UUID;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface MediaGenerationActivities {

    GenerationSubmitResult submit(UUID taskId);

    GenerationSubmitResult poll(UUID taskId);

    void settle(UUID taskId);

    @ActivityMethod(name = "FailMediaGeneration")
    void fail(UUID taskId, String errorMessage);
}
