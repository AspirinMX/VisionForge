package app.pagefault.visionforge.agent.provider;

public record ChatCompletionResult(
        String content,
        Integer inputTokens,
        Integer outputTokens,
        String finishReason,
        long latencyMs) {
}
