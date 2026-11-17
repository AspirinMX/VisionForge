package app.pagefault.visionforge.agent.tool;

import java.util.UUID;

public record ToolExecutionContext(
        UUID workspaceId,
        UUID conversationId,
        UUID userId,
        UUID agentRunId,
        String toolCallId,
        boolean platformAdmin) {
}
