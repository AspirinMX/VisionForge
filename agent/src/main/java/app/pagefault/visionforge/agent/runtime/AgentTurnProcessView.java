package app.pagefault.visionforge.agent.runtime;

import java.util.List;

/**
 * One turn inside an {@link AgentRun} process timeline: readable reasoning, optional intermediate
 * step narration before tool calls, and the ordered tool executions for that turn.
 */
public record AgentTurnProcessView(
        int turnNo,
        String reasoning,
        String stepText,
        List<ToolInvocationSummary> toolCalls) {
}
