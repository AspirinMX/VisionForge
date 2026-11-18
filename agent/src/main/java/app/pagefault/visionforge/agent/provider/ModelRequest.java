package app.pagefault.visionforge.agent.provider;

import java.util.List;

import app.pagefault.visionforge.agent.model.ReasoningEffort;

/**
 * One Responses API request. Credentials are resolved by the caller from the model definition and
 * never leave the provider layer.
 */
public record ModelRequest(
        String baseUrl,
        String apiKey,
        String modelId,
        ReasoningEffort reasoningEffort,
        int maxOutputTokens,
        String instructions,
        List<ModelInputItem> input,
        List<ModelToolSpec> tools,
        String promptCacheKey) {

    public ModelRequest {
        input = List.copyOf(input);
        tools = List.copyOf(tools);
    }
}
