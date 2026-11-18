package app.pagefault.visionforge.agent.temporal.agentrun;

import java.time.Duration;
import java.util.UUID;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

/**
 * Dynamic model/tool loop. Every model call and every tool execution is its own Activity so a
 * worker restart resumes from the last recorded step instead of repeating the whole run.
 */
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    private static final Duration AWAIT_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final int MAX_AWAIT_POLLS = 1_440;

    private final AgentActivities modelActivities = Workflow.newActivityStub(
            AgentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(15))
                    // A paid model call is never retried automatically; a failed turn ends the run.
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    private final AgentActivities toolActivities = Workflow.newActivityStub(
            AgentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    private final AgentActivities finalizeActivities = Workflow.newActivityStub(
            AgentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    private final AgentActivities awaitActivities = Workflow.newActivityStub(
            AgentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    @Override
    public void run(UUID runId) {
        try {
            modelActivities.loadPrompt(runId);
            int turnNo = 0;
            while (true) {
                ModelTurn turn = modelActivities.invokeModel(runId, turnNo);
                if (turn.toolCallIds().isEmpty()) {
                    finalizeActivities.completeRun(runId, turnNo);
                    return;
                }
                for (String toolCallId : turn.toolCallIds()) {
                    ToolExecution execution = toolActivities.executeTool(runId, toolCallId);
                    if (execution.waiting()) {
                        awaitGeneration(runId, toolCallId, execution.taskId());
                    }
                }
                turnNo++;
            }
        } catch (ActivityFailure failure) {
            finalizeActivities.failRun(runId, failureMessage(failure));
            throw failure;
        }
    }

    /**
     * Waits for a generation task without blocking an Activity. The model does not poll; the
     * completed task payload becomes the final result of the same tool call.
     */
    private void awaitGeneration(UUID runId, String toolCallId, UUID taskId) {
        int polls = 0;
        while (!awaitActivities.isGenerationTerminal(taskId) && polls++ < MAX_AWAIT_POLLS) {
            Workflow.sleep(AWAIT_POLL_INTERVAL);
        }
        awaitActivities.completeAwait(runId, toolCallId, taskId);
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
