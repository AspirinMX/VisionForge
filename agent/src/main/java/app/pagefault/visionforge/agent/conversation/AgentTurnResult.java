package app.pagefault.visionforge.agent.conversation;

import java.util.UUID;

public record AgentTurnResult(
        UUID runId,
        String workflowId,
        UUID userMessageId) {
}
