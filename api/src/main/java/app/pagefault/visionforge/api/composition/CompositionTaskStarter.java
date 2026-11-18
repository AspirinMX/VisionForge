package app.pagefault.visionforge.api.composition;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.agent.composition.CompositionTaskService;
import app.pagefault.visionforge.agent.temporal.composition.VideoCompositionWorkflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CompositionTaskStarter {

    private final WorkflowClient workflowClient;
    private final CompositionTaskService taskService;
    private final String taskQueue;

    public CompositionTaskStarter(
            WorkflowClient workflowClient,
            CompositionTaskService taskService,
            @Value("${visionforge.temporal.task-queue:vision-forge}") String taskQueue) {
        this.workflowClient = workflowClient;
        this.taskService = taskService;
        this.taskQueue = taskQueue;
    }

    public void start(UUID taskId, String workflowId) {
        try {
            VideoCompositionWorkflow workflow = workflowClient.newWorkflowStub(
                    VideoCompositionWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId)
                            .setTaskQueue(taskQueue)
                            .build());
            WorkflowClient.start(workflow::run, taskId);
            taskService.markWorkflowStarted(workflowId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Could not start composition workflow {}; leaving it pending for compensation",
                    workflowId,
                    exception);
            taskService.markWorkflowStartRetryable(workflowId, exception.getMessage());
        }
    }
}
