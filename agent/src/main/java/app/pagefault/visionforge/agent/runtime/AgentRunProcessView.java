package app.pagefault.visionforge.agent.runtime;

import java.util.List;
import java.util.UUID;

/**
 * Structured reasoning and tool timeline for an {@link AgentRun}.
 */
public record AgentRunProcessView(
        UUID runId,
        Long durationMs,
        List<AgentTurnProcessView> turns) {
}
