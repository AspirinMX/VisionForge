package app.pagefault.visionforge.agent.temporal.selfcheck;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface SelfCheckWorkflow {

    @WorkflowMethod
    String run(String input);
}
