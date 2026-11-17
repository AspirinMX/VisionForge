package app.pagefault.visionforge.agent.tool;

import java.util.UUID;

/**
 * Server-side execution context for a tool call. Identity never comes from model output.
 */
public record ToolExecutionContext(
        UUID userId,
        UUID conversationId,
        UUID agentRunId,
        String toolCallId) {
}
