package app.pagefault.visionforge.agent.temporal.composition;

import java.util.UUID;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface VideoCompositionWorkflow {

    @WorkflowMethod
    void run(UUID taskId);
}
