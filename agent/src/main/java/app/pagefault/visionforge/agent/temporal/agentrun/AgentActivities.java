package app.pagefault.visionforge.agent.temporal.agentrun;

import java.util.UUID;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface AgentActivities {

    AgentPrompt loadPrompt(UUID runId);

    ModelTurn invokeModel(UUID runId, int turnNo);

    ToolExecution executeTool(UUID runId, String toolCallId);

    boolean isGenerationTerminal(UUID taskId);

    void completeAwait(UUID runId, String toolCallId, UUID taskId);

    void completeRun(UUID runId, int turnNo);

    void failRun(UUID runId, String errorMessage);
}
