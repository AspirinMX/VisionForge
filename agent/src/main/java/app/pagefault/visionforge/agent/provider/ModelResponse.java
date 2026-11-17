package app.pagefault.visionforge.agent.provider;

import java.util.List;

/**
 * The fully accumulated result of one streamed Responses API call.
 */
public record ModelResponse(
        String responseId,
        String text,
        List<ModelToolCall> toolCalls,
        Integer inputTokens,
        Integer outputTokens,
        String finishReason,
        long latencyMs) {

    public ModelResponse {
        toolCalls = List.copyOf(toolCalls);
    }
}
