package app.pagefault.visionforge.agent.stream;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import app.pagefault.visionforge.agent.runtime.ToolInvocationSummary;
import app.pagefault.visionforge.agent.tool.ToolInvocationStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunStreamEvent(
        String type,
        String content,
        UUID assistantMessageId,
        String message,
        Integer turnNo,
        String callId,
        String toolName,
        String action,
        String target,
        ToolInvocationStatus status,
        UUID fileId,
        UUID taskId) {

    public AgentRunStreamEvent(
            String type, String content, UUID assistantMessageId, String message) {
        this(type, content, assistantMessageId, message, null, null, null, null, null, null, null, null);
    }

    public static AgentRunStreamEvent turnStart(int turnNo) {
        return new AgentRunStreamEvent(
                "turn_start", null, null, null, turnNo, null, null, null, null, null, null, null);
    }

    public static AgentRunStreamEvent delta(String content) {
        return new AgentRunStreamEvent("delta", content, null, null);
    }

    /** Plaintext chain of thought from providers that expose it. */
    public static AgentRunStreamEvent reasoning(String content) {
        return new AgentRunStreamEvent("reasoning", content, null, null);
    }

    /** Readable reasoning summary from models that only expose encrypted reasoning. */
    public static AgentRunStreamEvent reasoningSummary(String content) {
        return new AgentRunStreamEvent("reasoning_summary", content, null, null);
    }

    public static AgentRunStreamEvent toolStart(int turnNo, ToolInvocationSummary tool) {
        return toolEvent("tool_start", turnNo, tool);
    }

    public static AgentRunStreamEvent toolWaiting(int turnNo, ToolInvocationSummary tool) {
        return toolEvent("tool_waiting", turnNo, tool);
    }

    public static AgentRunStreamEvent toolDone(int turnNo, ToolInvocationSummary tool) {
        return toolEvent("tool_done", turnNo, tool);
    }

    public static AgentRunStreamEvent done(UUID assistantMessageId) {
        return new AgentRunStreamEvent("done", null, assistantMessageId, null);
    }

    public static AgentRunStreamEvent error(String message) {
        return new AgentRunStreamEvent("error", null, null, message);
    }

    private static AgentRunStreamEvent toolEvent(
            String type, int turnNo, ToolInvocationSummary tool) {
        return new AgentRunStreamEvent(
                type,
                null,
                null,
                tool.errorMessage(),
                turnNo,
                tool.callId(),
                tool.toolName(),
                tool.action(),
                tool.target(),
                tool.status(),
                tool.fileId(),
                tool.taskId());
    }
}
