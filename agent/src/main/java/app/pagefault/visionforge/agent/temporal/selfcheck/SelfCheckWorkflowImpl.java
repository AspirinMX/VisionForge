package app.pagefault.visionforge.agent.temporal.selfcheck;

import java.time.Duration;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

public class SelfCheckWorkflowImpl implements SelfCheckWorkflow {

    private final SelfCheckActivities activities = Workflow.newActivityStub(
            SelfCheckActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(5))
                    .build());

    @Override
    public String run(String input) {
        return activities.greet(input);
    }
}
