package app.pagefault.visionforge.agent.temporal.agentrun;

import java.time.Duration;
import java.util.UUID;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

/**
 * Minimal text-only AgentRun loop: load context, call the model once, persist the reply. Tool
 * calls and multi-step loops will extend this workflow without changing the API contract.
 */
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    private final AgentActivities modelActivities = Workflow.newActivityStub(
            AgentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    private final AgentActivities finalizeActivities = Workflow.newActivityStub(
            AgentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    @Override
    public void run(UUID runId) {
        try {
            AgentPrompt prompt = modelActivities.loadPrompt(runId);
            ModelReply reply = modelActivities.invokeModel(runId, prompt);
            finalizeActivities.completeRun(runId, reply);
        } catch (ActivityFailure failure) {
            finalizeActivities.failRun(runId, failureMessage(failure));
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
