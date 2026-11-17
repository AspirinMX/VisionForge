package app.pagefault.visionforge.agent.temporal.agentrun;

import java.util.List;

/**
 * Compact result of one model turn. The assistant text stays in the database; the workflow only
 * needs the tool call IDs to decide whether another step is required.
 */
public record ModelTurn(
        int turnNo,
        List<String> toolCallIds,
        String responseId) {

    public ModelTurn {
        toolCallIds = List.copyOf(toolCallIds);
    }
}
