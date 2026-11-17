package app.pagefault.visionforge.agent.temporal.generation;

import java.time.Duration;
import java.util.UUID;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

/**
 * Reliable media task execution. The workflow owns waiting and retry policy; all provider and
 * database work happens in activities.
 */
public class MediaGenerationWorkflowImpl implements MediaGenerationWorkflow {

    private static final int MAX_POLLS = 1_440;

    private final MediaGenerationActivities submitActivities = Workflow.newActivityStub(
            MediaGenerationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(15))
                    // A paid submit is never retried automatically.
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    private final MediaGenerationActivities pollActivities = Workflow.newActivityStub(
            MediaGenerationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    private final MediaGenerationActivities finalizeActivities = Workflow.newActivityStub(
            MediaGenerationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    private final Duration pollInterval;

    public MediaGenerationWorkflowImpl() {
        this(Duration.ofSeconds(5));
    }

    public MediaGenerationWorkflowImpl(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    @Override
    public void run(UUID taskId) {
        try {
            GenerationSubmitResult result = submitActivities.submit(taskId);
            if (result.finished()) {
                return;
            }
            int polls = 0;
            while (result.pending()) {
                if (polls++ >= MAX_POLLS) {
                    finalizeActivities.fail(taskId, "超过最长等待时间，结果待核对");
                    return;
                }
                Workflow.sleep(pollInterval);
                result = pollActivities.poll(taskId);
                if (result.finished()) {
                    return;
                }
            }
            finalizeActivities.settle(taskId);
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
