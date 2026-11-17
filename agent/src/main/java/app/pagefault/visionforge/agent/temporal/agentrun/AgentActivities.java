package app.pagefault.visionforge.agent.temporal.agentrun;

import java.util.UUID;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface AgentActivities {

    AgentPrompt loadPrompt(UUID runId);

    ModelTurn invokeModel(UUID runId, int turnNo);

    void executeTool(UUID runId, String toolCallId);

    void completeRun(UUID runId, int turnNo);

    void failRun(UUID runId, String errorMessage);
}
