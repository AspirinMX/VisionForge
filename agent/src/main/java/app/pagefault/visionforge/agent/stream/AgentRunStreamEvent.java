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

    public static AgentRunStreamEvent done(UUID assistantMessageId) {
        return new AgentRunStreamEvent("done", null, assistantMessageId, null);
    }

    public static AgentRunStreamEvent error(String message) {
        return new AgentRunStreamEvent("error", null, null, message);
    }
}
