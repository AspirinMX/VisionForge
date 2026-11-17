package app.pagefault.visionforge.worker.temporal;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequest;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentRunWorkflow;
import app.pagefault.visionforge.worker.config.WorkerProperties;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

/**
 * Retries workflow starts that were committed to the database but not confirmed by Temporal.
 */
@Component
@ConditionalOnProperty(
        prefix = "visionforge.worker.start-compensation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentRunStartCompensator {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStartCompensator.class);

    private final WorkflowClient workflowClient;
    private final AgentRunService agentRunService;
    private final WorkerProperties properties;
    private final String taskQueue;

    public AgentRunStartCompensator(
            WorkflowClient workflowClient,
            AgentRunService agentRunService,
            WorkerProperties properties,
            @Value("${visionforge.temporal.task-queue}") String taskQueue) {
        this.workflowClient = workflowClient;
        this.agentRunService = agentRunService;
        this.properties = properties;
        this.taskQueue = taskQueue;
    }

    @Scheduled(
            fixedDelayString = "${visionforge.worker.start-compensation.interval-ms:5000}",
            initialDelayString = "${visionforge.worker.start-compensation.initial-delay-ms:5000}")
    public void compensate() {
        WorkerProperties.StartCompensation config = properties.startCompensation();
        List<WorkflowStartRequest> requests =
                agentRunService.pendingStartRequests(config.batchSize());
        for (WorkflowStartRequest request : requests) {
            compensate(request, config);
        }
    }

    private void compensate(
            WorkflowStartRequest request, WorkerProperties.StartCompensation config) {
        try {
            AgentRunWorkflow workflow = workflowClient.newWorkflowStub(
                    AgentRunWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(request.getWorkflowId())
                            .setTaskQueue(taskQueue)
                            .build());
            WorkflowClient.start(workflow::run, request.getAgentRunId());
            agentRunService.markWorkflowStarted(request.getWorkflowId());
            log.info("Compensated AgentRun workflow start {}", request.getWorkflowId());
        } catch (WorkflowExecutionAlreadyStarted exception) {
            agentRunService.markWorkflowStarted(request.getWorkflowId());
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            log.warn(
                    "Could not compensate AgentRun workflow start {} (attempt {})",
                    request.getWorkflowId(),
                    request.getAttempts() + 1,
                    exception);
            agentRunService.recordWorkflowStartFailure(request, message, config.maxAttempts());
        }
    }
}
