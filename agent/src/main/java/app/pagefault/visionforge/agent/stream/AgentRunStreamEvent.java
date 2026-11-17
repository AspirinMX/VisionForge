package app.pagefault.visionforge.agent.stream;

import java.util.UUID;

public record AgentRunStreamEvent(
        String type,
        String content,
        UUID assistantMessageId,
        String message) {

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

    public static AgentRunStreamEvent done(UUID assistantMessageId) {
        return new AgentRunStreamEvent("done", null, assistantMessageId, null);
    }

    public static AgentRunStreamEvent error(String message) {
        return new AgentRunStreamEvent("error", null, null, message);
    }
}
