package app.pagefault.visionforge.agent.temporal.agentrun;

import java.util.UUID;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface AgentRunWorkflow {

    @WorkflowMethod
    void run(UUID runId);
}
