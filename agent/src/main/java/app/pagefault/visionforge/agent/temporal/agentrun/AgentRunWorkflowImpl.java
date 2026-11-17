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

    private static final int MAX_TURNS = 12;

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
                if (turnNo + 1 >= MAX_TURNS) {
                    finalizeActivities.failRun(
                            runId, "工具调用轮次超过上限（" + MAX_TURNS + "）");
                    return;
                }
                for (String toolCallId : turn.toolCallIds()) {
                    toolActivities.executeTool(runId, toolCallId);
                }
                turnNo++;
            }
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
