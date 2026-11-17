package app.pagefault.visionforge.agent.temporal.generation;

import java.util.UUID;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface MediaGenerationWorkflow {

    @WorkflowMethod
    void run(UUID taskId);
}
