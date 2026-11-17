package app.pagefault.visionforge.agent.provider;

public interface ChatModelClient {

    ChatCompletionResult complete(ChatCompletionRequest request);
}
