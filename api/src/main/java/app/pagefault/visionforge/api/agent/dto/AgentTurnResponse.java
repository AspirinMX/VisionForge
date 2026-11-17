package app.pagefault.visionforge.api.agent.dto;

import java.util.UUID;

import app.pagefault.visionforge.agent.conversation.AgentTurnResult;

public record AgentTurnResponse(
        UUID runId,
        String workflowId,
        UUID userMessageId) {

    public static AgentTurnResponse from(AgentTurnResult result) {
        return new AgentTurnResponse(result.runId(), result.workflowId(), result.userMessageId());
    }
}
