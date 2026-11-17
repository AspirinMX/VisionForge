package app.pagefault.visionforge.agent.runtime;

import java.util.List;

import app.pagefault.visionforge.agent.provider.ModelInputItem;

/**
 * The explicit Responses API input rebuilt from persisted messages, turns and tool results.
 */
public record AgentModelInput(String instructions, List<ModelInputItem> input) {

    public AgentModelInput {
        input = List.copyOf(input);
    }
}
