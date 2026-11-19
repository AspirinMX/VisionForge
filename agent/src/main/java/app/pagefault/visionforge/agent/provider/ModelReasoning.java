package app.pagefault.visionforge.agent.provider;

import java.util.List;

/**
 * One reasoning item returned by the model.
 *
 * <p>{@code encryptedContent} is the opaque stateless carrier used by OpenAI reasoning models;
 * {@code text} is the readable chain of thought returned by providers such as DeepSeek. Which
 * form is replayed depends on whether the model route declares its chain of thought as encrypted.
 */
public record ModelReasoning(
        String encryptedContent,
        String text,
        List<String> summary) {

    public ModelReasoning {
        summary = summary == null ? List.of() : List.copyOf(summary);
    }
}
