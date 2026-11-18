package app.pagefault.visionforge.agent.temporal.composition;

import java.time.Duration;
import java.util.UUID;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

public class VideoCompositionWorkflowImpl implements VideoCompositionWorkflow {

    private final CompositionActivities composeActivities = Workflow.newActivityStub(
            CompositionActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(30))
                    .setHeartbeatTimeout(Duration.ofSeconds(60))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    private final CompositionActivities finalizeActivities = Workflow.newActivityStub(
            CompositionActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    @Override
    public void run(UUID taskId) {
        try {
            composeActivities.compose(taskId);
        } catch (ActivityFailure failure) {
            finalizeActivities.fail(taskId, failureMessage(failure));
            throw failure;
        }
    }

    private String failureMessage(ActivityFailure failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof ApplicationFailure applicationFailure
                && applicationFailure.getMessage() != null) {
            return applicationFailure.getMessage();
        }
        return failure.getMessage();
    }
}
