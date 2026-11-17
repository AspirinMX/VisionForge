package app.pagefault.visionforge.agent.temporal.agentrun;

import java.util.UUID;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface AgentActivities {

    AgentPrompt loadPrompt(UUID runId);

    ModelReply invokeModel(UUID runId, AgentPrompt prompt);

    void completeRun(UUID runId, ModelReply reply);

    void failRun(UUID runId, String errorMessage);
}
