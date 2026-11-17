package app.pagefault.visionforge.api.generation;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.agent.temporal.generation.MediaGenerationWorkflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.extern.slf4j.Slf4j;

/**
 * Best-effort workflow start. The task, reservation and start request are already committed, so a
 * transient Temporal failure is left PENDING for the worker's compensator.
 */
@Slf4j
@Component
public class GenerationTaskStarter {

    private final WorkflowClient workflowClient;
    private final GenerationTaskService taskService;
    private final String taskQueue;

    public GenerationTaskStarter(
            WorkflowClient workflowClient,
            GenerationTaskService taskService,
            @Value("${visionforge.temporal.task-queue:vision-forge}") String taskQueue) {
        this.workflowClient = workflowClient;
        this.taskService = taskService;
        this.taskQueue = taskQueue;
    }

    public void start(UUID taskId, String workflowId) {
        try {
            MediaGenerationWorkflow workflow = workflowClient.newWorkflowStub(
                    MediaGenerationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId)
                            .setTaskQueue(taskQueue)
                            .build());
            WorkflowClient.start(workflow::run, taskId);
            taskService.markWorkflowStarted(workflowId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Could not start media generation workflow {}; leaving it pending for compensation",
                    workflowId,
                    exception);
            taskService.markWorkflowStartRetryable(workflowId, exception.getMessage());
        }
    }
}
