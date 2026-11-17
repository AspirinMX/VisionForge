package app.pagefault.visionforge.agent.provider;

import java.util.List;

import app.pagefault.visionforge.agent.model.ReasoningEffort;

public record ChatCompletionRequest(
        String baseUrl,
        String apiKey,
        String modelId,
        ReasoningEffort reasoningEffort,
        int maxOutputTokens,
        List<ChatTurn> turns) {

    public ChatCompletionRequest {
        turns = List.copyOf(turns);
    }
}
