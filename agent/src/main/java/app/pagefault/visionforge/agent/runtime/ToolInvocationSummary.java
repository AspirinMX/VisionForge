package app.pagefault.visionforge.agent.runtime;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.agent.tool.ToolInvocationStatus;

/**
 * UI-safe summary of one persisted or live tool call inside an {@link AgentRun}.
 */
public record ToolInvocationSummary(
        String callId,
        String toolName,
        String action,
        String target,
        ToolInvocationStatus status,
        UUID fileId,
        UUID taskId,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {
}
