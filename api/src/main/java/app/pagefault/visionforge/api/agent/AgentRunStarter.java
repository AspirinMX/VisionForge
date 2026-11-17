package app.pagefault.visionforge.api.agent;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentRunWorkflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.extern.slf4j.Slf4j;

/**
 * Best-effort workflow start. The user message and AgentRun are already committed, so a transient
 * Temporal failure is left as PENDING for the worker's start compensator instead of failing the
 * HTTP request.
 */
@Slf4j
@Component
public class AgentRunStarter {

    private final WorkflowClient workflowClient;
    private final AgentRunService agentRunService;
    private final String taskQueue;

    public AgentRunStarter(
            WorkflowClient workflowClient,
            AgentRunService agentRunService,
            @Value("${visionforge.temporal.task-queue:vision-forge}") String taskQueue) {
        this.workflowClient = workflowClient;
        this.agentRunService = agentRunService;
        this.taskQueue = taskQueue;
    }

    public void start(UUID runId, String workflowId) {
        try {
            AgentRunWorkflow workflow = workflowClient.newWorkflowStub(
                    AgentRunWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId)
                            .setTaskQueue(taskQueue)
                            .build());
            WorkflowClient.start(workflow::run, runId);
            agentRunService.markWorkflowStarted(workflowId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Could not start AgentRun workflow {}; leaving it pending for compensation",
                    workflowId,
                    exception);
            agentRunService.markWorkflowStartRetryable(workflowId, exception.getMessage());
        }
    }
}
