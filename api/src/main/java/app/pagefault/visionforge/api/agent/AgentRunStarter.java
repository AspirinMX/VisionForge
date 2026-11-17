package app.pagefault.visionforge.api.agent;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentRunWorkflow;
import app.pagefault.visionforge.common.error.BusinessException;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

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
            agentRunService.markWorkflowStartFailed(workflowId, exception.getMessage());
            throw new BusinessException(
                    AgentErrorCode.AGENT_RUN_START_FAILED, AgentErrorCode.AGENT_RUN_START_FAILED.message());
        }
    }
}
