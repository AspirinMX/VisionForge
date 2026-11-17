package app.pagefault.visionforge.agent.provider;

/**
 * One item of an explicit Responses API {@code input} array.
 *
 * <p>The conversation is always rebuilt from persisted messages, function calls and function call
 * outputs; {@code previous_response_id} is never used.
 */
public sealed interface ModelInputItem {

    record Message(String role, String content) implements ModelInputItem {
    }

    record FunctionCall(String callId, String name, String arguments) implements ModelInputItem {
    }

    record FunctionCallOutput(String callId, String output) implements ModelInputItem {
    }
}
