package app.pagefault.visionforge.worker.temporal;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.agent.composition.CompositionTaskService;
import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequest;
import app.pagefault.visionforge.agent.runtime.WorkflowStartType;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentRunWorkflow;
import app.pagefault.visionforge.agent.temporal.composition.VideoCompositionWorkflow;
import app.pagefault.visionforge.agent.temporal.generation.MediaGenerationWorkflow;
import app.pagefault.visionforge.worker.config.WorkerProperties;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

/**
 * Retries workflow starts that were committed to the database but not confirmed by Temporal.
 * The dispatch uses the persisted workflow type, never a class name from the request.
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
    private final GenerationTaskService generationTaskService;
    private final CompositionTaskService compositionTaskService;
    private final WorkerProperties properties;
    private final String taskQueue;

    public AgentRunStartCompensator(
            WorkflowClient workflowClient,
            AgentRunService agentRunService,
            GenerationTaskService generationTaskService,
            CompositionTaskService compositionTaskService,
            WorkerProperties properties,
            @Value("${visionforge.temporal.task-queue}") String taskQueue) {
        this.workflowClient = workflowClient;
        this.agentRunService = agentRunService;
        this.generationTaskService = generationTaskService;
        this.compositionTaskService = compositionTaskService;
        this.properties = properties;
        this.taskQueue = taskQueue;
    }

    @Scheduled(
            fixedDelayString = "${visionforge.worker.start-compensation.interval-ms:5000}",
            initialDelayString = "${visionforge.worker.start-compensation.initial-delay-ms:5000}")
    public void compensate() {
        WorkerProperties.StartCompensation config = properties.startCompensation();
        List<WorkflowStartRequest> agentRequests =
                agentRunService.pendingStartRequests(config.batchSize());
        for (WorkflowStartRequest request : agentRequests) {
            compensateAgent(request, config);
        }
        List<WorkflowStartRequest> mediaRequests =
                generationTaskService.pendingStartRequests(config.batchSize());
        for (WorkflowStartRequest request : mediaRequests) {
            compensateMedia(request, config);
        }
        List<WorkflowStartRequest> compositionRequests =
                compositionTaskService.pendingStartRequests(config.batchSize());
        for (WorkflowStartRequest request : compositionRequests) {
            compensateComposition(request, config);
        }
    }

    private void compensateAgent(
            WorkflowStartRequest request, WorkerProperties.StartCompensation config) {
        if (request.getWorkflowType() == WorkflowStartType.MEDIA_GENERATION) {
            compensateMedia(request, config);
            return;
        }
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
            String message = message(exception);
            log.warn(
                    "Could not compensate AgentRun workflow start {} (attempt {})",
                    request.getWorkflowId(),
                    request.getAttempts() + 1,
                    exception);
            agentRunService.recordWorkflowStartFailure(request, message, config.maxAttempts());
        }
    }

    private void compensateMedia(
            WorkflowStartRequest request, WorkerProperties.StartCompensation config) {
        try {
            MediaGenerationWorkflow workflow = workflowClient.newWorkflowStub(
                    MediaGenerationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(request.getWorkflowId())
                            .setTaskQueue(taskQueue)
                            .build());
            WorkflowClient.start(workflow::run, request.getBusinessId());
            generationTaskService.markWorkflowStarted(request.getWorkflowId());
            log.info("Compensated media generation workflow start {}", request.getWorkflowId());
        } catch (WorkflowExecutionAlreadyStarted exception) {
            generationTaskService.markWorkflowStarted(request.getWorkflowId());
        } catch (RuntimeException exception) {
            String message = message(exception);
            log.warn(
                    "Could not compensate media generation workflow start {} (attempt {})",
                    request.getWorkflowId(),
                    request.getAttempts() + 1,
                    exception);
            generationTaskService.recordWorkflowStartFailure(
                    request, message, config.maxAttempts());
        }
    }

    private void compensateComposition(
            WorkflowStartRequest request, WorkerProperties.StartCompensation config) {
        try {
            VideoCompositionWorkflow workflow = workflowClient.newWorkflowStub(
                    VideoCompositionWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(request.getWorkflowId())
                            .setTaskQueue(taskQueue)
                            .build());
            WorkflowClient.start(workflow::run, request.getBusinessId());
            compositionTaskService.markWorkflowStarted(request.getWorkflowId());
            log.info("Compensated composition workflow start {}", request.getWorkflowId());
        } catch (WorkflowExecutionAlreadyStarted exception) {
            compositionTaskService.markWorkflowStarted(request.getWorkflowId());
        } catch (RuntimeException exception) {
            String message = message(exception);
            log.warn(
                    "Could not compensate composition workflow start {} (attempt {})",
                    request.getWorkflowId(),
                    request.getAttempts() + 1,
                    exception);
            compositionTaskService.recordWorkflowStartFailure(
                    request, message, config.maxAttempts());
        }
    }

    private String message(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
