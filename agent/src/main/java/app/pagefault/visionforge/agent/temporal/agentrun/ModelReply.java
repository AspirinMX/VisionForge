package app.pagefault.visionforge.agent.temporal.agentrun;

public record ModelReply(
        String content,
        Integer inputTokens,
        Integer outputTokens,
        String finishReason,
        long latencyMs) {
}
