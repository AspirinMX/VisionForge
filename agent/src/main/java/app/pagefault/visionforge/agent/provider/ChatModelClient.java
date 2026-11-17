package app.pagefault.visionforge.agent.provider;

import java.util.function.Consumer;

public interface ChatModelClient {

    ChatCompletionResult complete(ChatCompletionRequest request);

    /**
     * Executes a streaming chat completion and invokes {@code onDelta} for each text fragment.
     * The returned result contains the fully accumulated content and usage when available.
     */
    ChatCompletionResult stream(ChatCompletionRequest request, Consumer<String> onDelta);
}
