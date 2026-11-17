package app.pagefault.visionforge.agent.provider;

import java.util.List;

/**
 * One item of an explicit Responses API {@code input} array.
 *
 * <p>The conversation is always rebuilt from persisted messages, function calls and function call
 * outputs; {@code previous_response_id} is never used.
 */
public sealed interface ModelInputItem {

    record Message(String role, String content) implements ModelInputItem {
    }

    /**
     * A user message with optional image attachments. Image URLs are short-lived and resolved by
     * the provider immediately before the call; they are never persisted in message history.
     */
    record UserMessage(String text, List<ImageInput> images) implements ModelInputItem {

        public UserMessage {
            images = images == null ? List.of() : List.copyOf(images);
        }
    }

    record ImageInput(String imageUrl, String detail) {
    }

    record FunctionCall(String callId, String name, String arguments) implements ModelInputItem {
    }

    record FunctionCallOutput(String callId, String output) implements ModelInputItem {
    }

    /**
     * A reasoning item replayed from an earlier turn of the same run. The server-side item id is
     * never submitted: with {@code store: false} the server has no record to resolve it against.
     */
    record Reasoning(String encryptedContent, String text, List<String> summary)
            implements ModelInputItem {

        public Reasoning {
            summary = summary == null ? List.of() : List.copyOf(summary);
        }
    }
}
