package app.pagefault.visionforge.agent.temporal.composition;

import java.util.UUID;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface CompositionActivities {

    void compose(UUID taskId);

    @ActivityMethod(name = "FailVideoComposition")
    void fail(UUID taskId, String errorMessage);
}
